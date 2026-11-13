package com.limupy.limuconnect.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Periodically snapshots the mods NeoForge actually loaded on the dedicated
 * server. This index is local staging data; it is not a public download list.
 */
@EventBusSubscriber(modid = "limuconnect", bus = EventBusSubscriber.Bus.GAME)
public final class LimuServerModSyncService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long CHECK_INTERVAL_MINUTES = 5L;
    private static ScheduledExecutorService executor;
    private static String lastFingerprint = "";

    private LimuServerModSyncService() {}

    @SubscribeEvent
    public static synchronized void onServerStarted(ServerStartedEvent event) {
        stopWorker();
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Limu-Mod-Sync-Index");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                LimuServerModSyncService::refreshIndex,
                0L,
                CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        LOGGER.info("[Limu Connect] Mod index watcher started; checks every {} minutes",
                CHECK_INTERVAL_MINUTES);
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        stopWorker();
    }

    private static synchronized void stopWorker() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void refreshIndex() {
        try {
            var jars = LimuServerModIndex.scan();
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("profile", "Limu");
            root.addProperty("minecraft_version", "1.21.1");
            root.addProperty("loader", "neoforge");
            root.addProperty("loader_version", "21.1.262");
            root.addProperty("generated_at", Instant.now().toString());

            JsonArray entries = new JsonArray();
            StringBuilder fingerprintSource = new StringBuilder();
            for (LimuServerModIndex.JarEntry jar : jars) {
                JsonObject item = new JsonObject();
                item.addProperty("file", jar.fileName());
                item.addProperty("size_bytes", jar.sizeBytes());
                item.addProperty("sha256", jar.sha256());
                JsonArray mods = new JsonArray();
                for (LimuServerModIndex.ModEntry mod : jar.mods()) {
                    JsonObject modItem = new JsonObject();
                    modItem.addProperty("id", mod.id());
                    modItem.addProperty("name", mod.name());
                    modItem.addProperty("version", mod.version());
                    mods.add(modItem);
                    fingerprintSource.append(mod.id()).append(':').append(mod.version()).append(';');
                }
                item.add("mods", mods);
                entries.add(item);
                fingerprintSource.append(jar.fileName()).append(':')
                        .append(jar.sha256()).append(';');
            }
            root.add("jars", entries);

            String fingerprint = sha256(fingerprintSource.toString());
            root.addProperty("fingerprint", fingerprint);
            Path directory = FMLPaths.GAMEDIR.get().resolve("config").resolve("limu-connect");
            Files.createDirectories(directory);
            Path target = directory.resolve("server-mod-index.json");
            Path temporary = Files.createTempFile(directory, ".server-mod-index-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }

            if (!fingerprint.equals(lastFingerprint)) {
                LOGGER.info("[Limu Connect] Server mod index changed: {} client-required JARs, fingerprint {}",
                        jars.size(), fingerprint.substring(0, 12));
                lastFingerprint = fingerprint;
            } else {
                LOGGER.debug("[Limu Connect] Server mod index unchanged ({} JARs)", jars.size());
            }
        } catch (Exception error) {
            LOGGER.error("[Limu Connect] Could not refresh server mod index; will retry in {} minutes",
                    CHECK_INTERVAL_MINUTES, error);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
