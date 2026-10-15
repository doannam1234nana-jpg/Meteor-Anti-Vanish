/*
 * "WATCHING YOU" alert, same sequence as the Anubis StaffGhost (GPL-3.0, credit 4ldenz):
 *   0.0s  staff face flashes across the screen (1.5s)
 *   1.25s red "WATCHING YOU" title + staff name subtitle
 *   4.5s  title cleared, gold "Goodluck Anubis User, Be Careful" fades in/out
 * The face is drawn in 2D (squash-to-fake-spin) instead of Anubis's ImGui 3D cube.
 */
package debug.util;

import java.util.UUID;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ColorHelper;

public final class WatchAlert {
    private static final float FACE_SECONDS = 1.5f;
    private static final float TITLE_DELAY = 1.25f;
    private static final float TITLE_END = 4.5f;
    private static final float NOTE_SECONDS = 4.0f;
    private static final Text NOTE = Text.literal("Goodluck Anubis User, Be Careful");
    private static final int NOTE_COLOR = 0xFFFF55;
    private static final int FACE_SIZE = 64;

    private static boolean registered;
    private static boolean active;
    private static int stage; // 0 = face, 1 = title shown, 2 = done
    private static long startNanos;
    private static long noteNanos;
    private static UUID watcher;
    private static String title = "";
    private static boolean titleEnabled = true;
    private static boolean faceEnabled = true;

    private WatchAlert() {}

    /** Name-only variant (used by Anti Vanish): resolves the UUID from the tab list. */
    public static void show(String name, int others, boolean title, boolean sound) {
        show(findId(name), name, others, title, sound);
    }

    public static void show(UUID id, String name, int others, boolean title, boolean sound) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null || name == null) return;
        if (!registered) {
            MeteorClient.EVENT_BUS.subscribe(WatchAlert.class);
            registered = true;
        }
        watcher = id;
        WatchAlert.title = others > 0 ? name + " +" + others : name;
        titleEnabled = title;
        faceEnabled = true;
        stage = 0;
        startNanos = System.nanoTime();
        noteNanos = 0L;
        active = true;
        if (sound) mc.player.playSound(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.0f);
    }

    private static UUID findId(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || name == null) return null;
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile().name().equalsIgnoreCase(name)) return entry.getProfile().id();
        }
        return null;
    }

    @EventHandler
    private static void onRender2D(Render2DEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            active = false;
            noteNanos = 0L;
            return;
        }
        if (active) tick(mc, event.drawContext);
        if (noteNanos != 0L) renderNote(mc, event.drawContext);
    }

    private static void tick(MinecraftClient mc, DrawContext g) {
        float seconds = (System.nanoTime() - startNanos) / 1.0E9f;
        if (stage == 0 && seconds >= TITLE_DELAY) {
            stage = 1;
            if (titleEnabled && mc.inGameHud != null) {
                mc.inGameHud.setTitleTicks(10, 40, 15);
                mc.inGameHud.setTitle(Text.literal("WATCHING YOU").formatted(Formatting.RED, Formatting.BOLD));
                mc.inGameHud.setSubtitle(Text.literal(title).formatted(Formatting.RED, Formatting.BOLD));
            }
        } else if (stage == 1 && seconds >= TITLE_END) {
            stage = 2;
            if (mc.inGameHud != null) mc.inGameHud.clearTitle();
            noteNanos = System.nanoTime();
            active = false;
            return;
        }
        if (faceEnabled && seconds < FACE_SECONDS) renderFace(mc, g, seconds / FACE_SECONDS);
    }

    private static void renderFace(MinecraftClient mc, DrawContext g, float f) {
        if (watcher == null || mc.getNetworkHandler() == null) return;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(watcher);
        if (entry == null) return;

        int w = g.getScaledWindowWidth();
        int h = g.getScaledWindowHeight();
        // Sweep from right to left, drifting slightly, "spinning" by squashing the width.
        double angle = Math.toRadians(60.0 - 150.0 * f);
        float spin = Math.max(0.25f, (float) Math.abs(Math.cos(angle)));
        float grow = 1.0f + 1.5f * (float) Math.sin(f * Math.PI); // biggest mid-flash
        float cx = w * (0.8f - 0.6f * f);
        float cy = h * 0.5f;

        g.getMatrices().pushMatrix();
        g.getMatrices().translate(cx, cy);
        g.getMatrices().scale(spin * grow, grow);
        PlayerSkinDrawer.draw(g, entry.getSkinTextures(), -FACE_SIZE / 2, -FACE_SIZE / 2, FACE_SIZE);
        g.getMatrices().popMatrix();
    }

    private static void renderNote(MinecraftClient mc, DrawContext g) {
        float t = (System.nanoTime() - noteNanos) / 1.0E9f;
        if (t >= NOTE_SECONDS) {
            noteNanos = 0L;
            return;
        }
        float fade = t < 0.5f ? t / 0.5f : Math.min(1.0f, (NOTE_SECONDS - t) / 1.0f);
        int alpha = Math.round(fade * 255.0f);
        if (alpha <= 8) return;

        TextRenderer font = mc.textRenderer;
        g.getMatrices().pushMatrix();
        g.getMatrices().translate(g.getScaledWindowWidth() / 2.0f, g.getScaledWindowHeight() / 2.0f);
        g.getMatrices().scale(2.0f, 2.0f);
        g.drawTextWithShadow(font, NOTE, -font.getWidth(NOTE) / 2, -font.fontHeight / 2,
            ColorHelper.withAlpha(alpha, NOTE_COLOR));
        g.getMatrices().popMatrix();
    }
}
