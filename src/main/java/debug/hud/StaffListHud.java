package debug.hud;

import debug.DebugAddon;
import debug.modules.StaffList;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** HUD list of online staff: status (VANISHED/SPECTATOR) and who is watching you (ported from the Anubis HUD). */
public class StaffListHud extends HudElement {
    public static final HudElementInfo<StaffListHud> INFO = new HudElementInfo<>(
        DebugAddon.HUD_GROUP, "staff-list-plus", "Online staff, vanish status and who is watching you.", StaffListHud::new);

    private static final Color RED = new Color(255, 85, 85);
    private static final Color YELLOW = new Color(255, 200, 70);
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color GRAY = new Color(170, 170, 170);

    private static final String WATCHING_YOU = "WATCHING YOU";
    private static final double COLUMN_GAP = 10;

    public StaffListHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        StaffList.HudList list;
        StaffList module = Modules.get().get(StaffList.class);

        if (isInEditor()) {
            list = new StaffList.HudList(List.of(
                new StaffList.HudRow("ExampleStaff", StaffList.Presence.ONLINE, true, 42000L, true, 23f, 0f),
                new StaffList.HudRow("VanishedMod", StaffList.Presence.VANISHED, false, -1L, false, -1f, 0f),
                new StaffList.HudRow("SpecAdmin", StaffList.Presence.SPECTATOR, false, -1L, false, -1f, 0f)), 0);
        } else if (module == null || !module.isActive()) {
            setSize(0, 0);
            return;
        } else {
            list = module.hudRows();
        }

        double lineH = renderer.textHeight(true) + 2;
        int total = list.rows().size() + list.more();
        String header = "Staff " + total;

        double width = renderer.textWidth(header, true);

        // First pass: column widths.
        double nameCol = 0;
        double watchCol = 0;
        for (StaffList.HudRow row : list.rows()) {
            nameCol = Math.max(nameCol, renderer.textWidth(nameText(row), true));
            if (row.watching()) watchCol = Math.max(watchCol, renderer.textWidth(watchText(row), true));
        }
        if (list.rows().isEmpty()) nameCol = renderer.textWidth("No staff online", true);
        if (list.more() > 0) nameCol = Math.max(nameCol, renderer.textWidth("+" + list.more() + " more", true));
        width = Math.max(width, nameCol + (watchCol > 0 ? COLUMN_GAP + watchCol : 0));

        int lines = 1 + Math.max(1, list.rows().size()) + (list.more() > 0 && !list.rows().isEmpty() ? 1 : 0);
        setSize(width, lineH * lines);

        double cx = x;
        double cy = y;
        renderer.text(header, cx, cy, GRAY, true);
        cy += lineH;

        if (list.rows().isEmpty()) {
            renderer.text("No staff online", cx, cy, GRAY, true);
            return;
        }

        for (StaffList.HudRow row : list.rows()) {
            Color nameColor = row.alertLevel() > 0f ? YELLOW : WHITE;
            renderer.text(row.name(), cx, cy, nameColor, true);
            if (row.presence() != StaffList.Presence.ONLINE) {
                double nameW = renderer.textWidth(row.name() + " ", true);
                boolean vanished = row.presence() == StaffList.Presence.VANISHED;
                renderer.text(vanished ? "VANISHED" : "SPECTATOR", cx + nameW, cy, vanished ? RED : YELLOW, true);
            }
            if (row.watching()) {
                double wx = cx + nameCol + COLUMN_GAP;
                renderer.text(WATCHING_YOU, wx, cy, RED, true);
                double tagW = renderer.textWidth(WATCHING_YOU + " ", true);
                renderer.text(valueText(row), wx + tagW, cy, WHITE, true);
            }
            cy += lineH;
        }
        if (list.more() > 0) {
            renderer.text("+" + list.more() + " more", cx, cy, GRAY, true);
        }
    }

    private static String nameText(StaffList.HudRow row) {
        return switch (row.presence()) {
            case VANISHED -> row.name() + " VANISHED";
            case SPECTATOR -> row.name() + " SPECTATOR";
            case ONLINE -> row.name();
        };
    }

    private static String valueText(StaffList.HudRow row) {
        return row.nearby() ? row.distanceLabel() : row.watchingFor();
    }

    private static String watchText(StaffList.HudRow row) {
        return WATCHING_YOU + " " + valueText(row);
    }
}
