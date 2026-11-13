package com.limupy.limuconnect.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limupy.limuconnect.LimuConnectMod;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

@EventBusSubscriber(
        modid = LimuConnectMod.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME,
        value = Dist.CLIENT
)
public final class ClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SERVER_NAME = "LimuServer";
    private static final String LEGACY_SERVER_NAME = "Limu Minecraft";
    // Minecraft may clear getCurrentServer() before DisconnectedScreen opens.
    // Remember the actual selected Limu row until the connection attempt ends.
    private static volatile boolean LIMU_CONNECTION_ATTEMPT = false;
    private static final byte[] LIMU_ICON = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAeElEQVR42u3aoREAIAwEQWQqSP9NRsPQAQy4rLiXEaszMnN2buyJiJYBAAAAAAAAAAAAAADg7VBVHQcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADoA/A4AAAAAAAAAAAAAAJ+iAAAAAAAAAAAA9wCdW3hJ4lrWB7gTAAAAAElFTkSuQmCC"
    );

    /*
     * Last verified address is only a bootstrap fallback. Once the public
     * directory responds, the current address replaces it automatically.
     */
    private static volatile String CURRENT_SERVER_ADDRESS = "";
    private static volatile boolean DIRECTORY_CONNECTED = false;
    private static volatile boolean DIRECTORY_CHECKED = false;
    private static volatile boolean DIRECTORY_CONFIRMED_OFFLINE = false;

    private static Field ROW_STATUS_ICON;
    private static Field ROW_STATUS_TOOLTIP;
    private static Object ONLINE_ICON;
    private static Object OFFLINE_ICON;
    private static Object UNKNOWN_ICON;
    private static boolean ROW_FIELDS_LOOKED_UP = false;
    private static final URI STATUS_URI = URI.create(
            "https://raw.githubusercontent.com/Limupy/Connect/main/limu/connect.json"
    );
    private static final Pattern E4STEAM_ADDRESS = Pattern.compile(
            "^(?:d-[a-z0-9-]+|[a-z0-9-]+)\\.steam$",
            Pattern.CASE_INSENSITIVE
    );
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /*
     * One refresh per multiplayer screen instance. A weak set avoids retaining
     * closed screens and prevents re-entry when the list is refreshed.
     */
    private static final Set<JoinMultiplayerScreen> REFRESHED_SCREENS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final long DIRECTORY_POLL_INTERVAL_MS = 3_000L;
    private static final AtomicBoolean DIRECTORY_REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long LAST_DIRECTORY_REQUEST_MS = 0L;

    private ClientEvents() {}

    /*
     * e4steam uses a P2P join transport, not the vanilla Java Status Ping
     * protocol. The latter can fail while e4steam still accepts logins.
     * Update the visible row from the public directory before it is rendered.
     * The server's actual address and normal Join action are left untouched.
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        // Do not depend on vanilla's localized Refresh button event: poll the
        // directory while the multiplayer screen is open. The request guard
        // prevents overlapping HTTP calls when rendering at 60+ FPS.
        long now = System.currentTimeMillis();
        if (now - LAST_DIRECTORY_REQUEST_MS >= DIRECTORY_POLL_INTERVAL_MS) {
            requestDirectoryRefresh(screen);
        }

        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof ServerSelectionList list)) {
                continue;
            }

            for (ServerSelectionList.Entry row : list.children()) {
                if (!(row instanceof ServerSelectionList.OnlineServerEntry onlineRow)) {
                    continue;
                }

                ServerData server = onlineRow.getServerData();
                if (!SERVER_NAME.equals(server.name)) {
                    continue;
                }

                decorateLimuServer(server);
                setRowStatusIcon(onlineRow, DIRECTORY_CONNECTED, DIRECTORY_CONFIRMED_OFFLINE);
            }
        }
    }

    private static void decorateLimuServer(ServerData server) {
        server.name = SERVER_NAME;
        server.setIconBytes(LIMU_ICON.clone());
        server.motd = Component.literal(CURRENT_SERVER_ADDRESS.isBlank()
                ? "Получение адреса сервера…"
                : "Адрес: " + CURRENT_SERVER_ADDRESS);
        server.version = Component.literal("Minecraft 1.21.1");
        server.status = Component.literal(
                DIRECTORY_CONNECTED ? "Сервер доступен" :
                        (DIRECTORY_CONFIRMED_OFFLINE ? "Сервер недоступен" :
                                (DIRECTORY_CHECKED ? "Статус не подтверждён" : "Проверка доступности…"))
        );

        if (DIRECTORY_CONNECTED) {
            // Display-only ping: a vanilla status ping is not a valid health
            // check for an e4steam P2P endpoint.
            server.ping = 35L;
            server.setState(ServerData.State.SUCCESSFUL);
        } else if (DIRECTORY_CONFIRMED_OFFLINE) {
            server.ping = -1L;
            server.setState(ServerData.State.UNREACHABLE);
        } else {
            // Neutral/pinging state is intentional when the directory is
            // stale or unreachable: vanilla's failed status ping is not proof
            // that the e4steam join transport is offline.
            server.ping = -2L;
            server.setState(ServerData.State.PINGING);
        }
    }

    private static void setRowStatusIcon(
            ServerSelectionList.OnlineServerEntry row,
            boolean connected,
            boolean confirmedOffline
    ) {
        try {
            if (!ROW_FIELDS_LOOKED_UP) {
                ROW_STATUS_ICON = row.getClass().getDeclaredField("statusIcon");
                ROW_STATUS_TOOLTIP = row.getClass().getDeclaredField("statusIconTooltip");
                ROW_STATUS_ICON.setAccessible(true);
                ROW_STATUS_TOOLTIP.setAccessible(true);

                Field online = ServerSelectionList.class.getDeclaredField("PING_5_SPRITE");
                Field offline = ServerSelectionList.class.getDeclaredField("UNREACHABLE_SPRITE");
                Field unknown = ServerSelectionList.class.getDeclaredField("PINGING_1_SPRITE");
                online.setAccessible(true);
                offline.setAccessible(true);
                unknown.setAccessible(true);
                ONLINE_ICON = online.get(null);
                OFFLINE_ICON = offline.get(null);
                UNKNOWN_ICON = unknown.get(null);
                ROW_FIELDS_LOOKED_UP = true;
            }

            Object icon = connected ? ONLINE_ICON :
                    (confirmedOffline ? OFFLINE_ICON : UNKNOWN_ICON);
            ROW_STATUS_ICON.set(row, icon);
            ROW_STATUS_TOOLTIP.set(
                    row,
                    Component.literal(
                            connected
                                    ? "Сервер доступен"
                                    : (confirmedOffline
                                            ? "Сервер сейчас недоступен"
                                            : "Проверка доступности сервера")
                    )
            );
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("[Limu Connect] Could not update vanilla row status icon", error);
        }
    }

    /*
     * e4steam connects through the Steam desktop client. Always explain a
     * failed Limu connection on Windows: checking only for a positively absent
     * steam.exe hid the hint whenever Steam was running but its P2P session
     * failed for another reason.
     */
    @SubscribeEvent
    public static void onDisconnectedScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DisconnectedScreen disconnected)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ServerData currentServer = minecraft.getCurrentServer();
        boolean limuAttempt = LIMU_CONNECTION_ATTEMPT
                || (currentServer != null && isLimuEntry(currentServer));
        LIMU_CONNECTION_ATTEMPT = false;
        if (!limuAttempt) {
            return;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        java.util.concurrent.CompletableFuture
                .supplyAsync(ClientEvents::isSteamRunning)
                .whenComplete((steamRunning, error) -> minecraft.execute(() -> {
                    if (minecraft.screen != disconnected) {
                        return;
                    }

                    String title;
                    String message;
                    if (error != null) {
                        title = "Не удалось подключиться к Limu";
                        message = "Limu использует Steam для P2P-подключения (e4steam). "
                                + "Не удалось проверить состояние Steam. Запустите Steam, войдите в аккаунт "
                                + "и попробуйте подключиться ещё раз.";
                    } else if (Boolean.FALSE.equals(steamRunning)) {
                        title = "Steam не запущен";
                        message = "Для подключения к Limu через e4steam необходимо запустить Steam. "
                                + "Откройте Steam, дождитесь входа в аккаунт и повторите подключение.";
                    } else {
                        title = "Ошибка подключения через Steam";
                        message = "Steam запущен, но P2P-подключение к Limu не установлено. "
                                + "Убедитесь, что вы вошли в Steam, перезапустите Steam и повторите попытку. "
                                + "Если ошибка повторяется, проверьте, что Steam не блокируется брандмауэром или VPN.";
                    }

                    // Keep Minecraft's original ConfirmScreen renderer from 1.2.5.
                    // Override only init() so the dialog has one centered action.
                    minecraft.setScreen(new ConfirmScreen(
                            ignored -> minecraft.setScreen(disconnected),
                            Component.literal(title),
                            Component.literal(message),
                            Component.literal("Понятно"),
                            Component.literal("Назад")
                    ) {
                        @Override
                        protected void addButtons(int y) {
                            int buttonWidth = 150;
                            addExitButton(Button.builder(
                                    Component.literal("Понятно"),
                                    button -> minecraft.setScreen(disconnected)
                            ).bounds(
                                    (width - buttonWidth) / 2,
                                    y,
                                    buttonWidth,
                                    20
                            ).build());
                        }
                    });
                }));
    }


    private static boolean isSteamRunning() {
        return ProcessHandle.allProcesses().anyMatch(process -> {
            ProcessHandle.Info info = process.info();
            String command = info.command().orElseGet(() -> info.commandLine().orElse(""));
            String normalized = command.toLowerCase(Locale.ROOT).replace('/', '\\');
            return normalized.equals("steam.exe") || normalized.endsWith("\\steam.exe");
        });
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }
        if (!REFRESHED_SCREENS.add(screen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ServerList servers = screen.getServers();

        /*
         * Show a normal vanilla entry immediately. Most importantly, do not
         * delete the saved entry while the network request is still pending:
         * GitHub may be unreachable on some clients, but the last known address
         * must remain visible and usable.
         */
        ensureLimuEntry(servers);
        servers.save();
        refreshVisibleList(screen, servers);

        requestDirectoryRefresh(screen);
    }

    @SubscribeEvent
    public static void onRefreshButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof JoinMultiplayerScreen screen)) return;
        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        // Capture the server row before vanilla starts connecting. On a failed
        // P2P join, Minecraft can clear currentServer before the disconnect UI.
        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                for (ServerSelectionList.Entry entry : list.children()) {
                    if (entry instanceof ServerSelectionList.OnlineServerEntry row
                            && row.isMouseOver(mouseX, mouseY)
                            && isLimuEntry(row.getServerData())) {
                        LIMU_CONNECTION_ATTEMPT = true;
                    }
                }
            }
        }
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractButton button)) continue;
            if (mouseX < button.getX() || mouseX >= button.getX() + button.getWidth()
                    || mouseY < button.getY() || mouseY >= button.getY() + button.getHeight()) continue;
            String label = button.getMessage().getString().toLowerCase(Locale.ROOT);
            if (label.contains("refresh") || label.contains("обновить") || label.contains("aktualisieren")
                    || label.contains("actualizar") || label.contains("rafraîchir")) {
                requestDirectoryRefresh(screen);
                return;
            }
        }
    }

    private static void requestDirectoryRefresh(JoinMultiplayerScreen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerList servers = screen.getServers();
        ensureLimuEntry(servers);
        servers.save();
        refreshVisibleList(screen, servers);

        if (!DIRECTORY_REQUEST_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        LAST_DIRECTORY_REQUEST_MS = System.currentTimeMillis();

        // GitHub raw CDN may cache the JSON despite request cache headers.
        // A per-request query token forces a fresh read after the address rotates.
        URI freshStatusUri = URI.create(STATUS_URI.toString() + "?nocache=" + System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(freshStatusUri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .header("User-Agent", "LimuConnect/1.1.9 Minecraft/1.21.1")
                .GET()
                .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Limu directory HTTP " + response.statusCode());
                    }
                    return parseCurrentServer(response.body());
                })
                .whenComplete((server, error) -> minecraft.execute(() -> {
                    DIRECTORY_REQUEST_IN_FLIGHT.set(false);
                    if (error != null || server == null) {
                        DIRECTORY_CHECKED = true;
                        DIRECTORY_CONNECTED = false;
                        DIRECTORY_CONFIRMED_OFFLINE = false;
                        LOGGER.warn("[Limu Connect] Directory refresh failed; keeping last known address", error);
                        if (minecraft.screen == screen) refreshVisibleList(screen, servers);
                        return;
                    }
                    DIRECTORY_CHECKED = true;
                    DIRECTORY_CONNECTED = server.fresh() && server.connected();
                    DIRECTORY_CONFIRMED_OFFLINE = server.fresh() && !server.connected();
                    // Address validity and heartbeat freshness are independent.
                    // Keep the join address synchronized even when the status timestamp
                    // is stale; freshness only controls the online/offline indicator.
                    CURRENT_SERVER_ADDRESS = server.address();
                    setLimuAddress(servers, CURRENT_SERVER_ADDRESS);
                    servers.save();
                    if (minecraft.screen == screen) refreshVisibleList(screen, servers);
                    LOGGER.info("[Limu Connect] Server entry refreshed: {}", CURRENT_SERVER_ADDRESS);
                }));
    }

    private static void ensureLimuEntry(ServerList servers) {
        ServerData retained = null;

        // Keep one existing entry, removing duplicates only.
        for (int i = servers.size() - 1; i >= 0; i--) {
            ServerData entry = servers.get(i);
            if (!isLimuEntry(entry)) {
                continue;
            }
            if (retained == null) {
                retained = entry;
            } else {
                servers.remove(entry);
            }
        }

        // A previous failed refresh may have erased the entry in older builds.
        if (retained == null) {
            retained = new ServerData(
                    SERVER_NAME,
                    "",
                    ServerData.Type.OTHER
            );
            servers.add(retained, false);
        }

        retained.name = SERVER_NAME;
        retained.setIconBytes(LIMU_ICON.clone());
        moveToTop(servers, retained);
    }

    private static void setLimuAddress(ServerList servers, String address) {
        removeLimuEntries(servers);
        ServerData entry = new ServerData(
                SERVER_NAME,
                address,
                ServerData.Type.OTHER
        );
        entry.setIconBytes(LIMU_ICON.clone());
        servers.add(entry, false);
        moveToTop(servers, entry);
    }

    private static void moveToTop(ServerList servers, ServerData entry) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i) == entry) {
                if (i > 0) {
                    servers.swap(i, 0);
                }
                return;
            }
        }
    }

    private static boolean isLimuEntry(ServerData server) {
        return SERVER_NAME.equals(server.name) || LEGACY_SERVER_NAME.equals(server.name);
    }

    private static void removeLimuEntries(ServerList servers) {
        for (int i = servers.size() - 1; i >= 0; i--) {
            if (isLimuEntry(servers.get(i))) {
                servers.remove(servers.get(i));
            }
        }
    }

    private static void refreshVisibleList(
            JoinMultiplayerScreen screen,
            ServerList servers
    ) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateOnlineServers(servers);
                return;
            }
        }
    }

    private static ServerEndpoint parseCurrentServer(String body) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            String state = getString(object, "state", "offline");
            String address = getString(object, "address", "");
            // updated_at is the publisher heartbeat; generated_at may describe
            // when the directory payload was first generated. Prefer the heartbeat.
            String timestamp = getString(
                    object,
                    "updated_at",
                    getString(object, "generated_at", "")
            );

            if (!E4STEAM_ADDRESS.matcher(address).matches()) {
                return null;
            }

            boolean fresh = false;
            if (!timestamp.isBlank()) {
                try {
                    Instant updated = Instant.parse(timestamp);
                    long ageSeconds = Duration.between(updated, Instant.now()).toSeconds();
                    fresh = ageSeconds >= -600 && ageSeconds <= 900;
                } catch (Exception ignored) {
                    // A valid address is still usable when the heartbeat is malformed.
                }
            }
            boolean serverRunning = getBoolean(object, "server_running", true);
            boolean transportConnected = getBoolean(object, "connected", false);
            boolean connected = fresh
                    && serverRunning
                    && transportConnected
                    && ("online".equalsIgnoreCase(state)
                        || "connected".equalsIgnoreCase(state));

            return new ServerEndpoint(address, connected, fresh);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getString(
            JsonObject object,
            String name,
            String fallback
    ) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsString()
                    : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(
            JsonObject object,
            String name,
            boolean fallback
    ) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsBoolean()
                    : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record ServerEndpoint(String address, boolean connected, boolean fresh) {}
}
