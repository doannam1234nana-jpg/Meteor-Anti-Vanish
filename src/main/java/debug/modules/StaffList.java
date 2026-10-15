/*
 * Staff List for Meteor Client.
 *
 * Ported from the Anubis client StaffListModule (GPL-3.0, credit 4ldenz):
 * lists online staff (from TAB), flags VANISHED / SPECTATOR staff, and raises a
 * "WATCHING YOU" alert when a staff member shows up near you or a hidden staff
 * member switches gamemode in your region.
 */
package debug.modules;

import debug.DebugAddon;
import debug.util.DonutStaff;
import debug.util.StaffRank;
import debug.util.WatchAlert;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

public class StaffList extends Module {
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int SETTLE_TICKS = 60;
    private static final int GROUP_THRESHOLD = 3;
    private static final int ALERT_GATHER_TICKS = 5;
    private static final int SWITCH_ECHO_TICKS = 20;
    private static final long ALERT_HIGHLIGHT_MS = 4000L;
    private static final long ALERT_COOLDOWN_MS = 120000L;
    private static final long MARK_MS = 600000L;
    private static final long WATCH_WINDOW_MS = 10000L;
    private static final long BODY_REAPPEAR_MS = 10000L;
    private static final long UNTRACKED_GRACE_MS = 10000L;
    private static final int MAX_ROWS = 8;
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");

    // ---- settings ----
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");
    private final SettingGroup sgStaff = settings.createGroup("Staff");

    private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alerts").description("Print staff joined / vanished / left in chat.").defaultValue(true).build());

    private final Setting<Boolean> watchAlert = sgAlerts.add(new BoolSetting.Builder()
        .name("watching-alert").description("Detect staff near you or switching gamemode in your region (WATCHING YOU).")
        .defaultValue(true).build());
    private final Setting<Boolean> titleAlert = sgAlerts.add(new BoolSetting.Builder()
        .name("title-alert").description("Show the big red WATCHING YOU title on screen.").defaultValue(true)
        .visible(watchAlert::get).build());
    private final Setting<Boolean> soundAlert = sgAlerts.add(new BoolSetting.Builder()
        .name("sound-alert").description("Play the guardian sound on WATCHING YOU.").defaultValue(true)
        .visible(watchAlert::get).build());

    private final Setting<List<String>> staffNames = sgStaff.add(new StringListSetting.Builder()
        .name("staff-names").description("Staff names to track. Default = DonutSMP staff list (from Anubis).")
        .defaultValue(new ArrayList<>(DonutStaff.DEFAULT)).build());

    // ---- runtime state ----
    private final Map<UUID, Staff> staff = new LinkedHashMap<>();
    private List<Staff> sorted = List.of();
    private boolean resort;
    private final Map<UUID, Sighting> sightings = new HashMap<>();
    private final ConcurrentLinkedQueue<GameModeSwitch> switches = new ConcurrentLinkedQueue<>();
    private final Map<UUID, String> pendingAlerts = new LinkedHashMap<>();
    private volatile Set<String> staffKeys = Set.of();
    private ClientPlayNetworkHandler connection;
    private Object lastWorld;
    private Object lastPlayer;
    private String lastBrand;
    private int ticksToScan;
    private int settleTicks;
    private boolean primed;
    private int alertGather;
    private int tickCount;

    public StaffList() {
        super(DebugAddon.CATEGORY, "staff-list-plus", "Lists online staff and warns when they are watching you (ported from Anubis).");
    }

    @Override
    public void onActivate() {
        forget();
        connection = mc.getNetworkHandler();
        settleTicks = 0;
        ticksToScan = 0;
        primed = false;
        lastWorld = null;
        lastPlayer = null;
        lastBrand = null;
        rebuildStaffKeys();
    }

    @Override
    public void onDeactivate() {
        forget();
        connection = null;
    }

    @Override
    public String getInfoString() {
        return sorted.isEmpty() ? null : Integer.toString(sorted.size());
    }

    private void forget() {
        staff.clear();
        sorted = List.of();
        forgetShard();
    }

    private void forgetShard() {
        switches.clear();
        sightings.clear();
        pendingAlerts.clear();
        resort = true;
    }

    // ================= HUD API =================

    public enum Presence {ONLINE, SPECTATOR, VANISHED}

    public record HudRow(String name, Presence presence, boolean watching, long watchingForMs, boolean nearby,
                         float distance, float alertLevel) {
        public String watchingFor() {
            return watching ? formatAge(watchingForMs) : "";
        }

        public String distanceLabel() {
            return nearby ? Math.round(distance) + "m" : "";
        }
    }

    public record HudList(List<HudRow> rows, int more) {
        public static final HudList EMPTY = new HudList(List.of(), 0);
    }

    public HudList hudRows() {
        List<Staff> all = sorted;
        int shown = all.size() <= MAX_ROWS ? all.size() : MAX_ROWS - 1;
        long now = System.currentTimeMillis();
        List<HudRow> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            Staff s = all.get(i);
            Sighting seen = sightings.get(s.id);
            boolean watching = seen != null && seen.watching(now);
            boolean nearby = seen != null && seen.nearby;
            float alert = seen == null ? 0f : Math.max(0f, Math.min(1f, (float) (seen.highlightUntil - now) / (float) ALERT_HIGHLIGHT_MS));
            rows.add(new HudRow(s.name, s.presence, watching, watching ? Math.max(0L, now - seen.watchingSince) : -1L,
                nearby, nearby ? seen.distance : -1f, alert));
        }
        return new HudList(List.copyOf(rows), all.size() - shown);
    }

    public static String formatAge(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long hours = total / 3600L;
        long minutes = total / 60L % 60L;
        long seconds = total % 60L;
        StringBuilder out = new StringBuilder(8);
        if (hours > 0L) out.append(hours).append(minutes < 10L ? ":0" : ":");
        return out.append(minutes).append(seconds < 10L ? ":0" : ":").append(seconds).toString();
    }

    // ================= events =================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // Network thread: only queue, process on the game thread.
        if (!watchAlert.get()) return;
        collectSwitches(event.packet);
    }

    private void collectSwitches(Packet<?> packet) {
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> inner : bundle.getPackets()) collectSwitches(inner);
            return;
        }
        if (!(packet instanceof PlayerListS2CPacket info)) return;
        if (!info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)
            || info.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;
        for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
            if (entry == null || entry.profileId() == null || entry.gameMode() == null) continue;
            switches.offer(new GameModeSwitch(entry.profileId(), entry.gameMode()));
        }
        while (switches.size() > 2048) switches.poll();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientPlayNetworkHandler conn = mc.getNetworkHandler();
        if (conn == null || mc.player == null || mc.world == null) {
            if (connection != null || !staff.isEmpty() || !sightings.isEmpty()) forget();
            switches.clear();
            connection = null;
            primed = false;
            lastWorld = null;
            lastPlayer = null;
            lastBrand = null;
            return;
        }

        boolean moved = worldMoved();
        if (conn != connection) {
            connection = conn;
            forget();
            settleTicks = SETTLE_TICKS;
            primed = false;
        } else if (moved) {
            forgetShard();
        }
        if (settleTicks > 0) settleTicks--;
        tickCount++;
        if (tickCount % 20 == 0) rebuildStaffKeys();

        long now = System.currentTimeMillis();
        if (watchAlert.get()) {
            drainSwitches(conn, now);
            trackBodies(now);
        }
        if (--ticksToScan <= 0) {
            ticksToScan = SCAN_INTERVAL_TICKS;
            Changes changes = scan(conn, now);
            if (primed) announce(changes);
            else if (settleTicks == 0) primed = true;
        }
        if (!pendingAlerts.isEmpty() && --alertGather <= 0) raise();
        if (resort) sortView(now);
    }

    // ================= staff names =================

    private void rebuildStaffKeys() {
        Set<String> keys = new HashSet<>();
        for (String name : staffNames.get()) {
            if (name != null && !name.isBlank()) keys.add(name.trim().toLowerCase(Locale.ROOT));
        }
        staffKeys = keys;
    }

    private boolean isStaffName(String name) {
        return name != null && staffKeys.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean isAccount(UUID id, String name) {
        return name != null && id.version() != 2 && ACCOUNT_NAME.matcher(name).matches();
    }

    // ================= TAB scan =================

    private Changes scan(ClientPlayNetworkHandler conn, long now) {
        UUID self = mc.player.getUuid();
        Set<UUID> listed = new HashSet<>();
        for (PlayerListEntry info : conn.getListedPlayerListEntries()) {
            if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
                listed.add(info.getProfile().id());
            }
        }

        Changes changes = new Changes();
        Set<UUID> inTab = new HashSet<>();
        for (PlayerListEntry info : conn.getPlayerList()) {
            if (info == null || info.getProfile() == null) continue;
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            if (id == null || id.equals(self) || !isStaffName(name) || !isAccount(id, name)) continue;
            inTab.add(id);

            StaffRank.Match rank = rank(info, name);
            Presence presence = info.getGameMode() == GameMode.SPECTATOR ? Presence.SPECTATOR
                : (listed.contains(id) ? Presence.ONLINE : Presence.VANISHED);

            Staff tracked = staff.get(id);
            if (tracked == null) {
                tracked = new Staff(id);
                staff.put(id, tracked);
                tracked.update(name, rank, presence);
                changes.joined.add(tracked);
                continue;
            }
            Presence before = tracked.presence;
            tracked.update(name, rank, presence);
            tracked.offTabScans = 0;
            if (before != presence) changes.changed.add(tracked);
        }

        Iterator<Staff> it = staff.values().iterator();
        while (it.hasNext()) {
            Staff tracked = it.next();
            if (inTab.contains(tracked.id)) continue;
            if (mc.world.getPlayerByUuid(tracked.id) != null) {
                // Gone from TAB but still standing next to us: vanished.
                if (++tracked.offTabScans < 2 || tracked.presence == Presence.VANISHED) continue;
                tracked.presence = Presence.VANISHED;
                changes.changed.add(tracked);
                continue;
            }
            it.remove();
            changes.left.add(tracked);
        }

        pruneSightings(now);
        sortView(now);
        return changes;
    }

    private StaffRank.Match rank(PlayerListEntry info, String name) {
        Team team = info.getScoreboardTeam();
        Text display = info.getDisplayName();
        Text prefix = team == null ? null : team.getPrefix();
        Text suffix = team == null ? null : team.getSuffix();
        return StaffRank.match(name, team == null ? null : team.getName(),
            display == null ? null : display.getString(),
            prefix == null ? null : prefix.getString(),
            suffix == null ? null : suffix.getString());
    }

    private void sortView(long now) {
        List<Staff> view = new ArrayList<>(staff.values());
        view.sort(Comparator.comparingInt((Staff s) -> group(s, now))
            .thenComparingInt(s -> s.seniority)
            .thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));
        sorted = List.copyOf(view);
        resort = false;
    }

    private int group(Staff s, long now) {
        Sighting seen = sightings.get(s.id);
        if (seen != null && seen.nearby) return 0;
        if (seen != null && seen.marked(now)) return 1;
        return s.presence != Presence.ONLINE ? 2 : 3;
    }

    // ================= watching detection =================

    /** A hidden staff member switching gamemode proves they are in our region. */
    private void drainSwitches(ClientPlayNetworkHandler conn, long now) {
        UUID self = mc.player.getUuid();
        GameModeSwitch change;
        while ((change = switches.poll()) != null) {
            UUID id = change.id();
            if (id.equals(self)) continue;
            String name;
            Staff tracked = staff.get(id);
            if (tracked != null) {
                name = tracked.name;
            } else {
                PlayerListEntry info = conn.getPlayerListEntry(id);
                name = info == null || info.getProfile() == null ? null : info.getProfile().name();
                if (!isStaffName(name) || !isAccount(id, name)) continue;
            }
            Sighting seen = sightings.computeIfAbsent(id, key -> new Sighting());
            if (!seen.marked(now)) resort = true;
            seen.prove(now);
            if (seen.coolingDown(now)) continue;
            seen.switchAlertTick = tickCount;
            seen.switchedToSpectator = change.mode() == GameMode.SPECTATOR;
            alert(id, seen, now, name);
        }
    }

    /** Staff whose body is loaded in the world next to us. */
    private void trackBodies(long now) {
        for (PlayerEntity body : mc.world.getPlayers()) {
            if (body == mc.player) continue;
            Staff tracked = staff.get(body.getUuid());
            if (tracked == null) continue;
            Sighting seen = sightings.computeIfAbsent(tracked.id, key -> new Sighting());
            seen.prove(now);
            seen.bodyTick = tickCount;
            seen.distance = mc.player.distanceTo(body);
            if (seen.nearby) continue;
            seen.nearby = true;
            resort = true;
            boolean newAppearance = seen.bodyLostAt == 0L || now - seen.bodyLostAt > BODY_REAPPEAR_MS;
            if (!newAppearance) continue;
            alert(tracked.id, seen, now, tracked.name);
        }
        for (Sighting seen : sightings.values()) {
            if (!seen.nearby || seen.bodyTick == tickCount) continue;
            seen.nearby = false;
            seen.bodyLostAt = now;
            resort = true;
        }
    }

    private void pruneSightings(long now) {
        Iterator<Map.Entry<UUID, Sighting>> it = sightings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Sighting> entry = it.next();
            Sighting seen = entry.getValue();
            if (staff.containsKey(entry.getKey())) {
                seen.untrackedSince = 0L;
                continue;
            }
            if (seen.untrackedSince == 0L) {
                seen.untrackedSince = now;
                continue;
            }
            if (now - seen.untrackedSince <= UNTRACKED_GRACE_MS) continue;
            it.remove();
            resort = true;
        }
    }

    private void alert(UUID id, Sighting seen, long now, String name) {
        seen.alertedAt = now;
        seen.highlightUntil = now + ALERT_HIGHLIGHT_MS;
        if (pendingAlerts.isEmpty()) alertGather = ALERT_GATHER_TICKS;
        pendingAlerts.put(id, name);
    }

    private void raise() {
        Map.Entry<UUID, String> first = pendingAlerts.entrySet().iterator().next();
        String name = first.getValue();
        int others = pendingAlerts.size() - 1;
        pendingAlerts.clear();
        WatchAlert.show(first.getKey(), name, others, titleAlert.get(), soundAlert.get());
    }

    // ================= chat =================

    private void announce(Changes changes) {
        changes.changed.removeIf(this::echoesSwitchAlert);
        if (changes.isEmpty() || !chatAlerts.get()) return;

        List<String> lines = new ArrayList<>();
        describe(lines, changes.joined, "joined", s -> {
            String note = note(s);
            return note == null ? "joined" : "joined (" + note + ")";
        });
        describe(lines, changes.changed, "changed visibility", s -> switch (s.presence) {
            case VANISHED -> "vanished" + (s.offTabScans > 0 ? " (off TAB, still nearby)" : " (hidden from TAB)");
            case SPECTATOR -> "switched to spectator";
            case ONLINE -> "is visible again";
        });
        describe(lines, changes.left, "left", s -> s.presence == Presence.VANISHED ? "is out of sight" : "left");

        for (String line : lines) warning("%s", line);

        boolean urgent = !changes.joined.isEmpty() || changes.changed.stream().anyMatch(s -> s.presence != Presence.ONLINE);
        if (urgent && mc.player != null) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.25f);
    }

    /** Skip the TAB-based "changed" line when the gamemode packet already raised a WATCHING YOU alert. */
    private boolean echoesSwitchAlert(Staff s) {
        Sighting seen = sightings.get(s.id);
        if (seen == null || seen.switchAlertTick < 0 || tickCount - seen.switchAlertTick > SWITCH_ECHO_TICKS) return false;
        return s.presence == Presence.SPECTATOR ? seen.switchedToSpectator
            : s.presence == Presence.ONLINE && !seen.switchedToSpectator;
    }

    private static void describe(List<String> lines, List<Staff> group, String groupVerb,
                                 java.util.function.Function<Staff, String> verb) {
        if (group.isEmpty()) return;
        if (group.size() > GROUP_THRESHOLD) {
            List<String> labels = new ArrayList<>(group.size());
            for (Staff s : group) labels.add(label(s));
            lines.add(group.size() + " staff " + groupVerb + ": " + String.join(", ", labels));
            return;
        }
        for (Staff s : group) lines.add(label(s) + " " + verb.apply(s));
    }

    private static String label(Staff s) {
        return s.name + " (" + s.rank + ")";
    }

    private static String note(Staff s) {
        return switch (s.presence) {
            case VANISHED -> "vanished";
            case SPECTATOR -> "spectating";
            case ONLINE -> null;
        };
    }

    // ================= helpers =================

    private boolean worldMoved() {
        Object nowWorld = mc.world;
        Object nowPlayer = mc.player;
        String nowBrand = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getBrand();
        boolean moved = lastWorld != null && (nowWorld != lastWorld
            || (nowPlayer != lastPlayer && mc.player != null && lastPlayer != null)
            || (nowBrand != null && lastBrand != null && !nowBrand.equals(lastBrand)));
        lastWorld = nowWorld;
        lastPlayer = nowPlayer;
        lastBrand = nowBrand;
        return moved;
    }

    // ================= data types =================

    private static final class Staff {
        final UUID id;
        String name = "";
        String rank = "";
        int seniority;
        Presence presence = Presence.ONLINE;
        int offTabScans;

        Staff(UUID id) {
            this.id = id;
        }

        void update(String name, StaffRank.Match rank, Presence presence) {
            this.name = name;
            this.rank = rank.label();
            this.seniority = rank.seniority();
            this.presence = presence;
        }
    }

    private static final class Sighting {
        long provenAt;
        long watchingSince;
        long alertedAt;
        long highlightUntil;
        boolean nearby;
        float distance;
        long bodyLostAt;
        int bodyTick;
        int switchAlertTick = -1;
        boolean switchedToSpectator;
        long untrackedSince;

        boolean marked(long now) {
            return nearby || (provenAt != 0L && now - provenAt < MARK_MS);
        }

        void prove(long now) {
            if (!watching(now)) watchingSince = now;
            provenAt = now;
        }

        boolean watching(long now) {
            return nearby || (provenAt != 0L && now - provenAt < WATCH_WINDOW_MS);
        }

        boolean coolingDown(long now) {
            return alertedAt != 0L && now - alertedAt < ALERT_COOLDOWN_MS;
        }
    }

    private record GameModeSwitch(UUID id, GameMode mode) {
    }

    private static final class Changes {
        final List<Staff> joined = new ArrayList<>();
        final List<Staff> changed = new ArrayList<>();
        final List<Staff> left = new ArrayList<>();

        boolean isEmpty() {
            return joined.isEmpty() && changed.isEmpty() && left.isEmpty();
        }
    }
}
