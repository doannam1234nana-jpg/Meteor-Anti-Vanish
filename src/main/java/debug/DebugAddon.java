package debug;

import debug.hud.AntiVanishHud;
import debug.hud.StaffListHud;
import debug.modules.AntiVanish;
import debug.modules.StaffList;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("debug");
    public static final Category CATEGORY = new Category("Debug");
    public static final HudGroup HUD_GROUP = new HudGroup("Debug");

    @Override
    public void onInitialize() {
        LOG.info("Debug addon (Anti Vanish + Staff List) loading");
        Modules.get().add(new AntiVanish());
        Modules.get().add(new StaffList());
        Hud.get().register(AntiVanishHud.INFO);
        Hud.get().register(StaffListHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "debug";
    }
}
