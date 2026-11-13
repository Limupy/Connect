package com.limupy.limuconnect.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Downloads only the client-required mods explicitly listed in Limu's signed-by-SHA256
 * manifest. Existing files are never overwritten or removed in this beta build.
 */
final class LimuModSyncManager {
    private static final URI MANIFEST_URI = URI.create(
            "https://raw.githubusercontent.com/Limupy/Connect/mod-sync/limu/mods.json"
    );
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "cdn.modrinth.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "mediafilez.forgecdn.net",
            "edge.forgecdn.net",
            "raw.githubusercontent.com"
    );
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final long MAX_MOD_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_MOD_COUNT = 160;

    private LimuModSyncManager() {}

    static CompletableFuture<SyncPlan> inspect() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(MANIFEST_URI)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .header("User-Agent", "LimuConnect-SyncBeta/1.3.0")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() != 200) {
                    closeQuietly(response.body());
                    throw new IllegalStateException("Манифест Limu недоступен: HTTP " + response.statusCode());
                }
                if (!isAllowedHost(response.uri())) {
                    closeQuietly(response.body());
                    throw new IllegalStateException("Недопустимый адрес манифеста");
                }

                byte[] bytes;
                try (InputStream input = response.body()) {
                    bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                }
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    throw new IllegalStateException("Манифест слишком большой");
                }

                JsonObject root = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
                int schema = root.get("schema_version").getAsInt();
                if (schema != 1) {
                    throw new IllegalStateException("Неподдерживаемая версия манифеста: " + schema);
                }

                String profile = requiredString(root, "profile");
                String minecraftVersion = requiredString(root, "minecraft_version");
                String loader = requiredString(root, "loader").toLowerCase(Locale.ROOT);
                String loaderVersion = requiredString(root, "loader_version");
                if (!"Limu".equalsIgnoreCase(profile)) {
                    throw new IllegalStateException("Манифест относится к другому профилю");
                }
                if (!"1.21.1".equals(minecraftVersion) || !"neoforge".equals(loader)) {
                    throw new IllegalStateException("Манифест не подходит для Minecraft 1.21.1 NeoForge");
                }

                Map<String, String> installed = new HashMap<>();
                for (IModInfo mod : ModList.get().getMods()) {
                    installed.putIfAbsent(mod.getModId(), mod.getVersion().toString());
                }

                String installedNeoForge = installed.get("neoforge");
                if (installedNeoForge != null && !installedNeoForge.equals(loaderVersion)) {
                    throw new IllegalStateException(
                            "Для Limu требуется NeoForge " + loaderVersion
                                    + ", установлен " + installedNeoForge
                    );
                }

                JsonArray jsonMods = root.getAsJsonArray("mods");
                if (jsonMods == null || jsonMods.size() > MAX_MOD_COUNT) {
                    throw new IllegalStateException("Некорректный список модов в манифесте");
                }

                List<ModEntry> missing = new ArrayList<>();
                List<VersionMismatch> mismatches = new ArrayList<>();
                Set<String> seenIds = new HashSet<>();
                Set<String> seenFiles = new HashSet<>();

                for (JsonElement element : jsonMods) {
                    if (!element.isJsonObject()) {
                        throw new IllegalStateException("Некорректная запись мода");
                    }
                    JsonObject item = element.getAsJsonObject();
                    ModEntry mod = parseEntry(item);
                    if (!seenIds.add(mod.id())) {
                        throw new IllegalStateException("Повторяющийся mod ID в манифесте: " + mod.id());
                    }
                    if (!seenFiles.add(mod.file().toLowerCase(Locale.ROOT))) {
                        throw new IllegalStateException("Повторяющееся имя файла в манифесте: " + mod.file());
                    }

                    String currentVersion = installed.get(mod.id());
                    if (currentVersion == null) {
                        missing.add(mod);
                    } else if (!currentVersion.equals(mod.version())) {
                        mismatches.add(new VersionMismatch(mod, currentVersion));
                    }
                }

                return new SyncPlan(List.copyOf(missing), List.copyOf(mismatches));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
    }

    static CompletableFuture<Integer> install(List<ModEntry> mods, Consumer<String> progress) {
        return CompletableFuture.supplyAsync(() -> {
            int installedCount = 0;
            try {
                Path modsDirectory = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
                Files.createDirectories(modsDirectory);
                long totalBytes = 0;

                for (int index = 0; index < mods.size(); index++) {
                    ModEntry mod = mods.get(index);
                    progress.accept("Загрузка " + (index + 1) + " из " + mods.size() + ": " + mod.name());

                    totalBytes += mod.sizeBytes();
                    if (totalBytes > 512L * 1024L * 1024L) {
                        throw new IllegalStateException("Общий размер пакета превышает лимит 512 МБ");
                    }
                    if (downloadOne(mod, modsDirectory)) {
                        installedCount++;
                    }
                }
                return installedCount;
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
    }

    private static boolean downloadOne(ModEntry mod, Path modsDirectory) throws Exception {
        Path target = modsDirectory.resolve(mod.file()).normalize();
        if (!target.getParent().equals(modsDirectory)) {
            throw new IllegalStateException("Недопустимый путь файла: " + mod.file());
        }

        if (Files.exists(target)) {
            String currentHash = sha256(target);
            if (currentHash.equalsIgnoreCase(mod.sha256())) {
                return false;
            }
            throw new IllegalStateException(
                    "Файл " + mod.file() + " уже существует, но его SHA-256 не совпадает. "
                            + "Он не был перезаписан."
            );
        }

        URI uri = URI.create(mod.url());
        if (!isAllowedHost(uri)) {
            throw new IllegalStateException("Источник не разрешён: " + uri.getHost());
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "LimuConnect-SyncBeta/1.3.0")
                .header("Accept", "application/java-archive, application/octet-stream, */*")
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IllegalStateException("Не удалось скачать " + mod.name() + ": HTTP " + response.statusCode());
        }
        if (!isAllowedHost(response.uri())) {
            closeQuietly(response.body());
            throw new IllegalStateException("Источник перенаправил на неразрешённый домен");
        }

        Path temporary = Files.createTempFile(modsDirectory, ".limu-sync-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_MOD_BYTES || written > mod.sizeBytes()) {
                        throw new IllegalStateException("Размер " + mod.file() + " превышает заявленный лимит");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            if (written != mod.sizeBytes()) {
                throw new IllegalStateException(
                        "Размер " + mod.file() + " не совпал: ожидалось "
                                + mod.sizeBytes() + ", получено " + written
                );
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(mod.sha256())) {
                throw new IllegalStateException("Проверка SHA-256 не пройдена для " + mod.file());
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ModEntry parseEntry(JsonObject item) {
        String id = requiredString(item, "id");
        String name = requiredString(item, "name");
        String version = requiredString(item, "version");
        String file = requiredString(item, "file");
        String url = requiredString(item, "url");
        String sha256 = requiredString(item, "sha256").toLowerCase(Locale.ROOT);
        long size = item.get("size_bytes").getAsLong();

        if (!id.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalStateException("Некорректный mod ID: " + id);
        }
        if (!file.matches("[A-Za-z0-9._+()%-]{1,180}\\.jar")) {
            throw new IllegalStateException("Некорректное имя JAR: " + file);
        }
        if (!sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Некорректный SHA-256 для " + id);
        }
        if (size < 1 || size > MAX_MOD_BYTES) {
            throw new IllegalStateException("Недопустимый размер JAR: " + file);
        }
        URI uri = URI.create(url);
        if (!isAllowedHost(uri)) {
            throw new IllegalStateException("Недопустимый источник загрузки для " + id);
        }
        return new ModEntry(id, name, version, file, url, sha256, size);
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalStateException("В манифесте отсутствует поле " + key);
        }
        String result = value.getAsString().trim();
        if (result.isEmpty()) {
            throw new IllegalStateException("Пустое поле " + key + " в манифесте");
        }
        return result;
    }

    private static boolean isAllowedHost(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && DOWNLOAD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    private static void closeQuietly(InputStream input) {
        try {
            if (input != null) input.close();
        } catch (Exception ignored) {
        }
    }

    record ModEntry(
            String id,
            String name,
            String version,
            String file,
            String url,
            String sha256,
            long sizeBytes
    ) {}

    record VersionMismatch(ModEntry mod, String installedVersion) {}

    record SyncPlan(List<ModEntry> missing, List<VersionMismatch> versionMismatches) {
        boolean isEmpty() {
            return missing.isEmpty() && versionMismatches.isEmpty();
        }
    }
}
