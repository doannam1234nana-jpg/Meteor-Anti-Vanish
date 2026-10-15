package debug.hud;

import debug.DebugAddon;
import debug.modules.AntiVanish;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** HUD list of current Anti Vanish detections (ported from the Anubis HUD). */
public class AntiVanishHud extends HudElement {
    public static final HudElementInfo<AntiVanishHud> INFO = new HudElementInfo<>(
        DebugAddon.HUD_GROUP, "anti-vanish", "Shows players detected by Anti Vanish.", AntiVanishHud::new);

    private static final Color RED = new Color(255, 85, 85);
    private static final Color YELLOW = new Color(255, 200, 70);
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color GRAY = new Color(170, 170, 170);

    public AntiVanishHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        List<AntiVanish.HudEntry> rows;
        AntiVanish module = Modules.get().get(AntiVanish.class);

        if (isInEditor()) {
            rows = List.of(new AntiVanish.HudEntry("CRITICAL", "ALERT", "Example"),
                new AntiVanish.HudEntry("Example", "VANISH", "Example"));
        } else if (module == null || !module.isActive()) {
            setSize(0, 0);
            return;
        } else {
            rows = module.hudEntries();
            if (rows.isEmpty()) {
                setSize(0, 0);
                return;
            }
        }

        double lineH = renderer.textHeight(true) + 2;
        double width = renderer.textWidth("Anti Vanish", true);
        for (AntiVanish.HudEntry e : rows) {
            width = Math.max(width, renderer.textWidth(e.tag() + "  " + e.value(), true));
        }
        setSize(width, lineH * (rows.size() + 1));

        double cx = x;
        double cy = y;
        renderer.text("Anti Vanish", cx, cy, GRAY, true);
        cy += lineH;
        for (AntiVanish.HudEntry e : rows) {
            Color tagColor = e.tag().equals("ALERT") ? RED : YELLOW;
            renderer.text(e.tag(), cx, cy, tagColor, true);
            double tagW = renderer.textWidth(e.tag() + "  ", true);
            renderer.text(e.value(), cx + tagW, cy, WHITE, true);
            cy += lineH;
        }
    }
}
