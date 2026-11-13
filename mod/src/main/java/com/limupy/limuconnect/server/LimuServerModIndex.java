package com.limupy.limuconnect.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * Builds a live index from the mods that NeoForge actually loaded on the
 * dedicated server. No hand-maintained mod list or remote download catalogue
 * is used. A jar is omitted only when all of its mods explicitly declare that
 * the server version may be ignored.
 */
public final class LimuServerModIndex {
    private static final Pattern MOD_TABLE = Pattern.compile(
            "(?ms)^\\[\\[mods]]\\s*(.*?)(?=^\\[\\[|\\z)"
    );
    private static final Pattern MOD_ID = Pattern.compile(
            "(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\""
    );
    private static final Pattern DISPLAY_TEST = Pattern.compile(
            "(?m)^\\s*displayTest\\s*=\\s*\"([^\"]+)\""
    );

    private LimuServerModIndex() {}

    public static List<JarEntry> scan() throws Exception {
        Map<Path, List<IModInfo>> byJar = new HashMap<>();
        for (IModInfo mod : ModList.get().getMods()) {
            String id = mod.getModId();
            if (id.equals("minecraft") || id.equals("neoforge") || id.equals("limuconnect")) {
                continue;
            }
            if (mod.getOwningFile() == null || mod.getOwningFile().getFile() == null) {
                continue;
            }
            IModFile file = mod.getOwningFile().getFile();
            Path path = file.getFilePath();
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            byJar.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new ArrayList<>()).add(mod);
        }

        List<JarEntry> result = new ArrayList<>();
        for (Map.Entry<Path, List<IModInfo>> item : byJar.entrySet()) {
            Path jar = item.getKey();
            Map<String, String> displayTests = readDisplayTests(jar);
            List<ModEntry> mods = new ArrayList<>();
            boolean anyClientRequired = false;

            for (IModInfo mod : item.getValue()) {
                String displayTest = displayTests.getOrDefault(mod.getModId(), "MATCH_VERSION")
                        .toUpperCase(Locale.ROOT);
                boolean clientRequired = !displayTest.equals("IGNORE_SERVER_VERSION")
                        && !displayTest.equals("IGNORE_ALL_VERSION");
                if (clientRequired) {
                    anyClientRequired = true;
                    mods.add(new ModEntry(
                            mod.getModId(),
                            mod.getDisplayName(),
                            mod.getVersion().toString()
                    ));
                }
            }

            if (!anyClientRequired) {
                continue;
            }

            result.add(new JarEntry(
                    jar.getFileName().toString(),
                    Files.size(jar),
                    sha256(jar),
                    jar,
                    List.copyOf(mods)
            ));
        }

        result.sort(Comparator.comparing(JarEntry::fileName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private static Map<String, String> readDisplayTests(Path jar) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                entry = zip.getEntry("META-INF/mods.toml");
            }
            if (entry == null) {
                return result;
            }

            String toml;
            try (InputStream input = zip.getInputStream(entry)) {
                toml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            Matcher tables = MOD_TABLE.matcher(toml);
            while (tables.find()) {
                String table = tables.group(1);
                Matcher id = MOD_ID.matcher(table);
                if (!id.find()) {
                    continue;
                }
                Matcher display = DISPLAY_TEST.matcher(table);
                result.put(id.group(1), display.find() ? display.group(1) : "MATCH_VERSION");
            }
        }
        return result;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    public record ModEntry(String id, String name, String version) {}

    public record JarEntry(
            String fileName,
            long sizeBytes,
            String sha256,
            Path source,
            List<ModEntry> mods
    ) {}
}
