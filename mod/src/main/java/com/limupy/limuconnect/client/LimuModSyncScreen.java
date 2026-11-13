package com.limupy.limuconnect.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Consent and progress UI for the Limu client mod synchronizer.
 */
final class LimuModSyncScreen extends Screen {
    private final Screen returnScreen;
    private final List<LimuModSyncManager.ModEntry> mods;
    private volatile String progress = "Подготовка загрузки…";
    private volatile boolean finished;

    private LimuModSyncScreen(
            Screen returnScreen,
            List<LimuModSyncManager.ModEntry> mods
    ) {
        super(Component.literal("Синхронизация модов Limu"));
        this.returnScreen = returnScreen;
        this.mods = List.copyOf(mods);
    }

    static void showPrompt(
            Minecraft minecraft,
            Screen returnScreen,
            LimuModSyncManager.SyncPlan plan
    ) {
        if (plan.missing().isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "В клиенте найдены моды Limu с другими версиями. "
                            + "Бета-синхронизатор пока не перезаписывает существующие JAR-файлы.\n\n"
            );
            for (LimuModSyncManager.VersionMismatch mismatch : plan.versionMismatches()) {
                message.append(mismatch.mod().name())
                        .append(": установлена ")
                        .append(mismatch.installedVersion())
                        .append(", требуется ")
                        .append(mismatch.mod().version())
                        .append('\n');
            }
            minecraft.setScreen(singleButton(
                    minecraft,
                    returnScreen,
                    "Требуется обновление модов",
                    message.toString()
            ));
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Для подключения к Limu не хватает ")
                .append(plan.missing().size())
                .append(" мод(ов):\n\n");
        appendModNames(message, plan.missing());

        if (!plan.versionMismatches().isEmpty()) {
            message.append("\nУже установленные моды с другими версиями не будут перезаписаны.\n");
        }
        message.append("\nЗагрузить недостающие файлы из проверенных источников?");

        minecraft.setScreen(new ConfirmScreen(
                accepted -> {
                    if (accepted) {
                        minecraft.setScreen(new LimuModSyncScreen(returnScreen, plan.missing()));
                        ((LimuModSyncScreen) minecraft.screen).startDownload();
                    } else {
                        minecraft.setScreen(returnScreen);
                    }
                },
                Component.literal("Не хватает модов Limu"),
                Component.literal(message.toString()),
                Component.literal("Скачать и установить"),
                Component.literal("Отмена")
        ));
    }

    private static void appendModNames(
            StringBuilder message,
            List<LimuModSyncManager.ModEntry> mods
    ) {
        int displayed = Math.min(mods.size(), 12);
        for (int i = 0; i < displayed; i++) {
            LimuModSyncManager.ModEntry mod = mods.get(i);
            message.append("• ")
                    .append(mod.name())
                    .append(" ")
                    .append(mod.version())
                    .append('\n');
        }
        if (mods.size() > displayed) {
            message.append("и ещё ")
                    .append(mods.size() - displayed)
                    .append("…\n");
        }
    }

    private static ConfirmScreen singleButton(
            Minecraft minecraft,
            Screen returnScreen,
            String title,
            String message
    ) {
        return new ConfirmScreen(
                ignored -> minecraft.setScreen(returnScreen),
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
                        button -> minecraft.setScreen(returnScreen)
                ).bounds(
                        (width - buttonWidth) / 2,
                        y,
                        buttonWidth,
                        20
                ).build());
            }
        };
    }

    private void startDownload() {
        LimuModSyncManager.install(mods, next -> {
            Minecraft.getInstance().execute(() -> progress = next);
        }).whenComplete((count, error) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) {
                return;
            }
            finished = true;
            if (error == null) {
                progress = "Скачано и проверено файлов: " + count + ".";
                Minecraft.getInstance().setScreen(singleButton(
                        Minecraft.getInstance(),
                        returnScreen,
                        "Моды установлены",
                        progress + "\n\nЗакрой Minecraft и запусти его заново. "
                                + "NeoForge загрузит новые моды при следующем запуске."
                ));
            } else {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                String detail = cause.getMessage() == null
                        ? cause.getClass().getSimpleName()
                        : cause.getMessage();
                Minecraft.getInstance().setScreen(singleButton(
                        Minecraft.getInstance(),
                        returnScreen,
                        "Не удалось установить моды",
                        "Загрузка остановлена. Уже проверенные файлы сохранены, "
                                + "непроверенные временные файлы удалены.\n\n" + detail
                ));
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(
                font,
                Component.literal("Синхронизация модов Limu"),
                width / 2,
                height / 2 - 42,
                0xFFFFFF
        );
        graphics.drawWordWrap(
                font,
                Component.literal(progress),
                40,
                height / 2 - 18,
                Math.max(200, width - 80),
                0xFFFFFF
        );
        if (!finished) {
            graphics.drawCenteredString(
                    font,
                    Component.literal("Не закрывай игру во время загрузки"),
                    width / 2,
                    height / 2 + 42,
                    0xAAAAAA
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
