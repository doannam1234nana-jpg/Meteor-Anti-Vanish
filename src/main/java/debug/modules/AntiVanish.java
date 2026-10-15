/*
 * Anti Vanish for Meteor Client.
 *
 * Detection logic ported from the Anubis client (GPL-3.0, credit 4ldenz):
 * tab/spectator/command-probe/invisible/sound/particle detection, staff list and "critical" scoring.
 * This addon is therefore also GPL-3.0.
 */
package debug.modules;

import com.mojang.brigadier.suggestion.Suggestion;
import debug.DebugAddon;
import debug.util.DonutStaff;
import debug.util.VanishText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

public class AntiVanish extends Module {
    private static final String PROBE_COMMAND = "minecraft:msg";
    private static final long SIGNAL_WINDOW_MS = 15000L;
    private static final long DETECTION_TTL_MS = 20000L;
    private static final long CRITICAL_COOLDOWN_MS = 12000L;
    private static final long ANNOUNCE_COOLDOWN_MS = 1500L;
    private static final long ANNOUNCE_WINDOW_MS = 4000L;
    private static final int MAX_ANNOUNCE_PER_WINDOW = 6;
    private static final int MAX_OBSERVATIONS_PER_TICK = 512;
    private static final long SELF_PLACE_TTL_MS = 6000L;
    private static final long CONTAINER_SELF_GRACE_MS = 2500L;
    private static final long RECENT_MESSAGE_TTL_MS = 8000L;
    private static final long TRUSTED_LISTED_MS = 1500L;
    private static final long SIGHTING_TTL_MS = 600000L;
    private static final double EXPLOSION_RADIUS = 8.0;

    // ---- settings ----
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect = settings.createGroup("Detection");
    private final SettingGroup sgStaff = settings.createGroup("Staff");
    private final SettingGroup sgCritical = settings.createGroup("Critical");

    private final Setting<Boolean> chatAlerts = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-alerts").description("Print detections in chat.").defaultValue(true).build());
    private final Setting<Integer> sensorRange = sgGeneral.add(new IntSetting.Builder()
        .name("sensor-range").description("Range (blocks) for invisible/sound/particle sensors.")
        .defaultValue(64).min(16).sliderRange(16, 128).build());

    private final Setting<Boolean> tabWatch = sgDetect.add(new BoolSetting.Builder()
        .name("tab-watch").description("Player hidden/removed from TAB without a leave message.").defaultValue(true).build());
    private final Setting<Boolean> spectatorWatch = sgDetect.add(new BoolSetting.Builder()
        .name("spectator-watch").description("Player switched to spectator.").defaultValue(true).build());
    private final Setting<Boolean> commandProbe = sgDetect.add(new BoolSetting.Builder()
        .name("command-probe").description("Tab-complete /msg periodically; players online but missing from TAB are flagged.")
        .defaultValue(true).build());
    private final Setting<Integer> probeInterval = sgDetect.add(new IntSetting.Builder()
        .name("probe-interval").description("Ticks between command probes.").defaultValue(100).min(20).sliderRange(20, 400)
        .visible(commandProbe::get).build());
    private final Setting<Boolean> invisiblePlayers = sgDetect.add(new BoolSetting.Builder()
        .name("invisible-players").description("Invisible player entities nearby (noisy on servers with invisibility potions).")
        .defaultValue(true).build());
    private final Setting<Boolean> soundDetection = sgDetect.add(new BoolSetting.Builder()
        .name("sound-detection").description("Chest/door/button/lever sounds with no visible cause.").defaultValue(true).build());
    private final Setting<Boolean> particleDetection = sgDetect.add(new BoolSetting.Builder()
        .name("particle-detection").description("Crit/smoke/block particles with no visible cause.").defaultValue(true).build());

    private final Setting<Boolean> staffOnly = sgStaff.add(new BoolSetting.Builder()
        .name("staff-only").description("Only track players in the staff list below. Off = track everyone (very noisy).")
        .defaultValue(true).build());
    private final Setting<List<String>> staffNames = sgStaff.add(new StringListSetting.Builder()
        .name("staff-names").description("Names to watch. Default = DonutSMP staff list (from Anubis).")
        .defaultValue(new ArrayList<>(DonutStaff.DEFAULT)).build());

    private final Setting<Boolean> criticalEnabled = sgCritical.add(new BoolSetting.Builder()
        .name("critical").description("Raise a CRITICAL alert when several different signals add up.").defaultValue(true).build());
    private final Setting<Integer> criticalScore = sgCritical.add(new IntSetting.Builder()
        .name("critical-score").description("Combined signal score needed for CRITICAL (needs 2+ signal types).")
        .defaultValue(35).min(10).sliderRange(10, 100).visible(criticalEnabled::get).build());
    private final Setting<Boolean> chime = sgCritical.add(new BoolSetting.Builder()
        .name("chime").description("Play a sound on CRITICAL.").defaultValue(true).visible(criticalEnabled::get).build());

    // ---- runtime state ----
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final Map<UUID, KnownPlayer> knownPlayers = new HashMap<>();
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private final Map<String, Long> signalCooldowns = new HashMap<>();
    private final Map<String, Long> announceCooldowns = new HashMap<>();
    private final Deque<Long> announceTimes = new ArrayDeque<>();
    private final Map<Long, Long> selfPlacedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmedDepartures = new HashMap<>();
    private final Map<Long, Long> automatedMechanisms = new HashMap<>();
    private final Map<String, Deque<Long>> weakParticleBursts = new HashMap<>();
    private final Deque<Signal> signals = new ArrayDeque<>();
    private final Deque<ExplosionEvent> recentExplosions = new ArrayDeque<>();
    private final Deque<PendingVanish> pendingVanishes = new ArrayDeque<>();
    private final Map<String, HiddenSpell> hiddenSpells = new LinkedHashMap<>();
    private final Deque<RecentMessage> recentMessages = new ArrayDeque<>();
    private final Map<UUID, Long> listedSinceMs = new HashMap<>();
    private final Map<UUID, Long> sightings = new HashMap<>();
    private final Set<Integer> completionRequestIds = ConcurrentHashMap.newKeySet();
    private volatile List<String> pendingCompletionNames;
    private volatile Set<String> staffKeys = Set.of();
    private boolean serverSendsLeaveMessages;
    private int nextCompletionId = 30000;
    private int tickCounter;
    private volatile long lastLocalActionMs;
    private long lastContainerActivityMs;
    private long lastCriticalMs;
    private long criticalUntilMs;
    private String criticalWatcher = "";
    private boolean pendingChime;
    private Object lastWorld;
    private Object lastPlayer;
    private String lastBrand;

    public AntiVanish() {
        super(DebugAddon.CATEGORY, "anti-vanish", "Detects vanished staff (ported from Anubis).");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        rebuildStaffKeys();
        if (mc.player != null && mc.world != null) trackListedPlayers();
    }

    @Override
    public void onDeactivate() {
        resetRuntime();
    }

    @Override
    public String getInfoString() {
        long now = System.currentTimeMillis();
        return now < criticalUntilMs ? "CRITICAL" : null;
    }

    // ================= events =================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetRuntime();
            return;
        }
        if (worldMoved()) resetRuntime();
        if (tickCounter % 20 == 0) rebuildStaffKeys();
        playPendingChime();
        tickCounter++;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            lastContainerActivityMs = System.currentTimeMillis();
        }
        trackSightings();
        drainObservations();
        processPendingVanishes();
        processCompletionProbe();
        if (commandProbe.get() && tickCounter % probeInterval.get() == 0) sendCompletionProbe();
        if (tickCounter % 10 == 0) {
            trackListedPlayers();
            reviewSpells();
        }
        if (invisiblePlayers.get() && tickCounter % 5 == 0) scanInvisiblePlayers();
        pruneState();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        handleOutbound(event.packet);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // Runs on the network thread: only queue observations here, process them on the game thread.
        if (handleInbound(event.packet)) event.cancel();
    }

    // ================= packets =================

    private void handleOutbound(Packet<?> packet) {
        if (packet == null) return;
        if (packet instanceof PlayerInteractBlockC2SPacket useOn) {
            lastLocalActionMs = System.currentTimeMillis();
            if (useOn.getBlockHitResult() != null && useOn.getBlockHitResult().getBlockPos() != null) {
                long until = System.currentTimeMillis() + SELF_PLACE_TTL_MS;
                BlockPos hit = useOn.getBlockHitResult().getBlockPos();
                BlockPos adjacent = hit.offset(useOn.getBlockHitResult().getSide());
                selfPlacedBlocks.put(hit.asLong(), until);
                selfPlacedBlocks.put(adjacent.asLong(), until);
                String itemPath = "";
                if (mc.player != null && useOn.getHand() != null) {
                    Identifier itemId = Registries.ITEM.getId(mc.player.getStackInHand(useOn.getHand()).getItem());
                    itemPath = VanishText.path(itemId == null ? "" : itemId.toString());
                }
                markSelfMultiBlockFootprint(hit, itemPath, selfPlacedBlocks, until);
                markSelfMultiBlockFootprint(adjacent, itemPath, selfPlacedBlocks, until);
            }
            return;
        }
        if (packet instanceof PlayerActionC2SPacket || packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket || packet instanceof HandSwingC2SPacket) {
            lastLocalActionMs = System.currentTimeMillis();
        }
    }

    static void markSelfMultiBlockFootprint(BlockPos pos, String path, Map<Long, Long> targets, long until) {
        if (pos == null || targets == null) return;
        targets.put(pos.asLong(), until);
        String id = path == null ? "" : path;
        boolean vertical = (id.endsWith("_door") && !id.endsWith("trapdoor")) || id.contains("sunflower")
            || id.contains("lilac") || id.contains("rose_bush") || id.contains("peony") || id.contains("tall_grass")
            || id.contains("large_fern") || id.contains("pitcher_plant");
        if (vertical) {
            targets.put(pos.up().asLong(), until);
            targets.put(pos.down().asLong(), until);
        }
        if (id.endsWith("_bed")) {
            targets.put(pos.add(1, 0, 0).asLong(), until);
            targets.put(pos.add(-1, 0, 0).asLong(), until);
            targets.put(pos.add(0, 0, 1).asLong(), until);
            targets.put(pos.add(0, 0, -1).asLong(), until);
        }
    }

    /** @return true if the packet should be cancelled (our own completion probe reply). */
    private boolean handleInbound(Packet<?> packet) {
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> inner : bundle.getPackets()) handleInbound(inner);
            return false;
        }
        if (packet instanceof PlayerRemoveS2CPacket remove) {
            if (tabWatch.get() && remove.profileIds().size() < 4) {
                for (UUID id : remove.profileIds()) observations.offer(Observation.tabRemove(id));
            }
        } else if (packet instanceof PlayerListS2CPacket info) {
            if (info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) {
                if (tabWatch.get()) {
                    int unlisted = 0;
                    for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                        if (entry != null && !entry.listed()) unlisted++;
                    }
                    if (unlisted > 0 && unlisted < 4) {
                        for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
                            if (entry != null && !entry.listed()) observations.offer(Observation.tabHide(entry.profileId()));
                        }
                    }
                }
            } else if (info.getActions().contains(PlayerListS2CPacket.Action.UPDATE_GAME_MODE)
                && spectatorWatch.get()) {
                queueGamemodeUpdates(info);
            }
        } else if (packet instanceof GameMessageS2CPacket chat) {
            String text = chat.content() == null ? "" : chat.content().getString();
            if (!text.isBlank()) observations.offer(Observation.systemChat(text));
            String departed = departedPlayerName(chat.content());
            if (!departed.isBlank()) observations.offer(Observation.playerLeft(departed));
        } else if (packet instanceof CommandSuggestionsS2CPacket suggestions) {
            if (completionRequestIds.remove(suggestions.id())) {
                List<String> names = new ArrayList<>();
                for (Suggestion s : suggestions.getSuggestions().getList()) {
                    String text = s.getText();
                    if (text == null || text.isBlank()) continue;
                    names.add(text.trim());
                }
                pendingCompletionNames = names;
                return true;
            }
        } else if (packet instanceof EntityTrackerUpdateS2CPacket metadata) {
            if (invisiblePlayers.get()) observations.offer(Observation.entityMetadata(metadata.id()));
        } else if (packet instanceof ExplosionS2CPacket explosion) {
            Vec3d c = explosion.center();
            observations.offer(Observation.explosion(c));
        } else if (packet instanceof PlaySoundFromEntityS2CPacket sound) {
            if (soundDetection.get()) {
                observations.offer(Observation.entitySound(sound.getEntityId(), soundId(sound.getSound())));
            }
        } else if (packet instanceof ParticleS2CPacket particles) {
            if (particleDetection.get()) {
                Identifier id = Registries.PARTICLE_TYPE.getId(particles.getParameters().getType());
                observations.offer(Observation.position(ObservationType.PARTICLE,
                    particles.getX(), particles.getY(), particles.getZ(), id == null ? "" : id.toString()));
            }
        } else if (packet instanceof PlaySoundS2CPacket sound) {
            if (soundDetection.get()) {
                observations.offer(Observation.position(ObservationType.POSITIONAL_SOUND,
                    sound.getX(), sound.getY(), sound.getZ(), soundId(sound.getSound())));
            }
        }
        return false;
    }

    private static String soundId(RegistryEntry<SoundEvent> entry) {
        if (entry == null || entry.value() == null || entry.value().id() == null) return "";
        return entry.value().id().toString();
    }

    private static String departedPlayerName(Text component) {
        if (component == null) return "";
        TextContent content = component.getContent();
        if (!(content instanceof TranslatableTextContent translated)
            || !"multiplayer.player.left".equals(translated.getKey())) return "";
        Object[] args = translated.getArgs();
        if (args.length == 0 || args[0] == null) return "";
        return args[0] instanceof Text name ? name.getString() : String.valueOf(args[0]);
    }

    private void queueGamemodeUpdates(PlayerListS2CPacket info) {
        if (info.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;
        for (PlayerListS2CPacket.Entry entry : info.getEntries()) {
            if (entry == null || entry.gameMode() == null || entry.profileId() == null) continue;
            observations.offer(Observation.gamemode(entry.profileId(), entry.gameMode().name()));
        }
    }

    // ================= staff =================

    private void rebuildStaffKeys() {
        Set<String> keys = new HashSet<>();
        for (String name : staffNames.get()) {
            if (name != null && !name.isBlank()) keys.add(name.trim().toLowerCase(Locale.ROOT));
        }
        staffKeys = keys;
    }

    private boolean isStaffName(String name) {
        if (name == null) return false;
        return !staffOnly.get() || staffKeys.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Remember staff whose body we have seen in the world (used as "same region" proof). */
    private void trackSightings() {
        long now = System.currentTimeMillis();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (isStaffName(profileName(p))) sightings.put(p.getUuid(), now);
        }
    }

    // ================= observation processing =================

    private void drainObservations() {
        Observation o;
        for (int i = 0; i < MAX_OBSERVATIONS_PER_TICK && (o = observations.poll()) != null; i++) {
            processObservation(o);
        }
        while (observations.size() > 4096) observations.poll();
    }

    private void processObservation(Observation o) {
        switch (o.type) {
            case TAB_REMOVE -> handleTabRemoval(o.profileId);
            case TAB_HIDE -> handleTabHidden(o.profileId);
            case PLAYER_LEFT -> {
                serverSendsLeaveMessages = true;
                confirmDeparture(o.detail);
            }
            case SYSTEM_CHAT -> cacheRecentMessage(o.detail);
            case ENTITY_METADATA -> inspectInvisibleEntity(o.entityId);
            case POSITIONAL_SOUND -> inspectPositionalSound(o);
            case ENTITY_SOUND -> inspectEntitySound(o);
            case PARTICLE -> inspectParticle(o);
            case EXPLOSION -> rememberExplosion(o);
            case GAMEMODE -> announceGamemode(o.profileId, o.detail);
        }
    }

    private void trackListedPlayers() {
        if (mc.getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        for (PlayerListEntry info : mc.getNetworkHandler().getListedPlayerListEntries()) {
            if (info == null || info.getProfile() == null || info.getProfile().id() == null) continue;
            UUID uuid = info.getProfile().id();
            if (mc.player != null && mc.player.getUuid().equals(uuid)) continue;
            listedSinceMs.putIfAbsent(uuid, now);
            String name = info.getProfile().name();
            if (name != null && !knownPlayers.containsKey(uuid)) knownPlayers.put(uuid, new KnownPlayer(uuid, name));
            if (name == null || hiddenSpells.isEmpty()) continue;
            endSpell(name, false, 1000L);
        }
        if (listedSinceMs.size() > 1024 || knownPlayers.size() > 1024) {
            Set<UUID> connected = new HashSet<>();
            for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
                if (info != null && info.getProfile() != null) connected.add(info.getProfile().id());
            }
            listedSinceMs.keySet().retainAll(connected);
            knownPlayers.keySet().retainAll(connected);
        }
    }

    private boolean trustedListed(UUID uuid) {
        Long since = uuid == null ? null : listedSinceMs.get(uuid);
        return since != null && System.currentTimeMillis() - since >= TRUSTED_LISTED_MS;
    }

    private boolean credibleSubject(UUID uuid, String name) {
        return uuid != null && VanishText.isUsername(name) && isStaffName(name) && trustedListed(uuid);
    }

    private void handleTabRemoval(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;
        String name = knownName(uuid);
        if (!credibleSubject(uuid, name) || !VanishText.isPlausiblePlayerName(name)) return;
        pendingVanishes.removeIf(p -> p.uuid.equals(uuid));
        pendingVanishes.addLast(new PendingVanish(uuid, name, tickCounter + 20));
    }

    private void handleTabHidden(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;
        KnownPlayer known = knownPlayers.get(uuid);
        String name = known != null && known.name != null ? known.name : knownName(uuid);
        if (!credibleSubject(uuid, name) || !VanishText.isPlausiblePlayerName(name)) return;
        if (recentMessageNames(name)) return;
        reportHidden(uuid, name, false, "Vanish Event: hidden from TAB", "hidden from TAB", 100);
    }

    private String knownName(UUID uuid) {
        KnownPlayer known = knownPlayers.get(uuid);
        if (known != null && known.name != null && !known.name.isBlank()) return known.name;
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null && info.getProfile() != null) return info.getProfile().name();
        }
        return null;
    }

    private UUID knownUuid(String name) {
        for (KnownPlayer known : knownPlayers.values()) {
            if (known.name.equalsIgnoreCase(name)) return known.uuid;
        }
        return null;
    }

    private void confirmDeparture(String displayedName) {
        long now = System.currentTimeMillis();
        for (KnownPlayer known : knownPlayers.values()) {
            if (!VanishText.containsPlayerName(displayedName, known.name)) continue;
            confirmedDepartures.put(known.uuid, now + 5000L);
            pendingVanishes.removeIf(p -> p.uuid.equals(known.uuid));
            hiddenSpells.remove(spellKey(known.name));
            Detection d = detections.get(known.uuid.toString());
            if (d != null && tabDepartureReason(d.reason)) detections.remove(known.uuid.toString());
            signals.removeIf(s -> s.type == SignalType.VANISH && s.subject.equalsIgnoreCase(known.name)
                && tabDepartureReason(s.reason));
        }
    }

    private void processPendingVanishes() {
        while (!pendingVanishes.isEmpty() && pendingVanishes.peekFirst().dueTick <= tickCounter) {
            PendingVanish pending = pendingVanishes.removeFirst();
            if (mc.getNetworkHandler().getPlayerListEntry(pending.uuid) != null) continue;
            long now = System.currentTimeMillis();
            if (confirmedDepartures.getOrDefault(pending.uuid, 0L) > now) continue;
            if (recentMessageNames(pending.name)) {
                serverSendsLeaveMessages = true;
                continue;
            }
            PlayerEntity remaining = mc.world.getPlayerByUuid(pending.uuid);
            if (remaining != null && !remaining.isRemoved()) {
                reportHidden(pending.uuid, pending.name, false, "Vanish Event: entity remained", "off TAB, still nearby", 100);
                continue;
            }
            String reason = "Vanish Event: silent TAB disappearance";
            int score = serverSendsLeaveMessages ? 100 : 30;
            if (serverSendsLeaveMessages) {
                reportHidden(pending.uuid, pending.name, false, reason, "left TAB silently", score);
                continue;
            }
            RegionProof proof = regionProof(pending.uuid);
            if (proof == null) continue;
            signals.addLast(new Signal(SignalType.VANISH, pending.name, proof, reason, score, now));
            evaluateCritical();
        }
    }

    static boolean tabDepartureReason(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.startsWith("vanish event:")
            && (lower.contains("tab") || lower.contains("entity remained") || lower.contains("no leave packet"));
    }

    private void cacheRecentMessage(String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        recentMessages.addLast(new RecentMessage(text, now));
        while (!(recentMessages.isEmpty()
            || (now - recentMessages.peekFirst().atMs <= RECENT_MESSAGE_TTL_MS && recentMessages.size() <= 64))) {
            recentMessages.removeFirst();
        }
    }

    private boolean recentMessageNames(String name) {
        if (name == null || name.isBlank()) return false;
        long now = System.currentTimeMillis();
        for (RecentMessage m : recentMessages) {
            if (now - m.atMs > RECENT_MESSAGE_TTL_MS) continue;
            if (VanishText.looksLikeLeaveMessage(m.text, name)) return true;
        }
        return false;
    }

    // ================= command probe =================

    private void sendCompletionProbe() {
        if (mc.getNetworkHandler() == null) return;
        int id = nextCompletionId++;
        if (nextCompletionId > 40000) nextCompletionId = 30000;
        completionRequestIds.add(id);
        while (completionRequestIds.size() > 8) completionRequestIds.remove(completionRequestIds.iterator().next());
        try {
            mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, PROBE_COMMAND + " "));
        } catch (Throwable ignored) {
        }
    }

    private void processCompletionProbe() {
        List<String> current = pendingCompletionNames;
        if (current == null) return;
        pendingCompletionNames = null;
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        Set<String> tabNames = new HashSet<>();
        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info == null || info.getProfile() == null || info.getProfile().name() == null) continue;
            tabNames.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        }
        String self = mc.player.getName().getString();
        List<String> hidden = new ArrayList<>();
        for (String name : current) {
            if (!isStaffName(name) || !VanishText.isPlausiblePlayerName(name) || name.equalsIgnoreCase(self)
                || tabNames.contains(name.toLowerCase(Locale.ROOT)) || recentMessageNames(name)) continue;
            hidden.add(name);
        }
        if (hidden.isEmpty() || hidden.size() > 3) return;
        for (String name : hidden) {
            reportHidden(knownUuid(name), name, false, "Vanish Event: hidden but targetable", "off TAB, still targetable", 90);
        }
    }

    // ================= hidden "spells" (a vanish that is being tracked) =================

    private void reportHidden(UUID uuid, String name, boolean spectator, String reason, String detail, int score) {
        upsertDetection(detectionKey(uuid, name), name, reason, score, System.currentTimeMillis() + DETECTION_TTL_MS);
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell != null && spell.spectator == spectator) return;
        hiddenSpells.put(spellKey(name), new HiddenSpell(uuid, name, spectator, detail, regionProof(uuid), tickCounter));
    }

    private void reviewSpells() {
        Iterator<HiddenSpell> it = hiddenSpells.values().iterator();
        while (it.hasNext()) {
            HiddenSpell spell = it.next();
            if (spell.spectator && mc.getNetworkHandler().getPlayerListEntry(spell.uuid) == null) {
                it.remove();
                continue;
            }
            if (spell.watched || spell.sinceTick >= tickCounter) continue;
            RegionProof proof = regionProof(spell.uuid);
            if (proof == null && !spell.told) proof = spell.proof;
            if (proof != null) {
                spell.told = true;
                spell.watched = true;
                watchingYou(spell.name, proof);
                continue;
            }
            if (spell.told) continue;
            spell.told = true;
            news(spell.name, spell.spectator ? "switched to spectator" : "vanished", spell.detail);
        }
    }

    private boolean endSpell(String name, boolean spectator, long settleMs) {
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell == null || spell.spectator != spectator || System.currentTimeMillis() - spell.sinceMs < settleMs) return false;
        hiddenSpells.remove(spellKey(name));
        detections.remove(detectionKey(spell.uuid, spell.name));
        if (spell.told) news(spell.name, "is visible again", "");
        return true;
    }

    private RegionProof regionProof(UUID uuid) {
        if (uuid == null) return null;
        PlayerEntity body = mc.world.getPlayerByUuid(uuid);
        if (body != null && body != mc.player) return new RegionProof(uuid, Math.round(body.distanceTo(mc.player)));
        Long seen = sightings.get(uuid);
        if (seen != null && System.currentTimeMillis() - seen < SIGHTING_TTL_MS) return new RegionProof(uuid, -1);
        return null;
    }

    private static String spellKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String detectionKey(UUID uuid, String name) {
        return uuid != null ? uuid.toString() : name;
    }

    // ================= invisible players =================

    private void scanInvisiblePlayers() {
        double rangeSq = sensorRangeSq();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player == mc.player || !player.isInvisible()
                || player.squaredDistanceTo(mc.player) > rangeSq || !realPlayer(player)) continue;
            triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
        }
    }

    private void inspectInvisibleEntity(int entityId) {
        Entity entity = mc.world.getEntityById(entityId);
        if (!(entity instanceof PlayerEntity player) || player == mc.player || !player.isInvisible()) return;
        if (player.squaredDistanceTo(mc.player) > sensorRangeSq()) return;
        if (!realPlayer(player)) return;
        triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
    }

    private boolean realPlayer(PlayerEntity player) {
        return credibleSubject(player.getUuid(), profileName(player));
    }

    private static String profileName(PlayerEntity player) {
        return player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().name();
    }

    // ================= sound / particle sensors =================

    private void inspectPositionalSound(Observation o) {
        if (!VanishText.suspiciousSound(o.detail)) return;
        Vec3d source = o.position();
        long now = System.currentTimeMillis();
        if (!nearPlayer(source) || hasVisibleCause(source) || isExplosionRelated(source)
            || isPoweredMechanism(source, o.detail) || now - lastLocalActionMs < 1000L
            || recentlySelfPlaced(source) || villagerToggledDoor(o.detail, source)
            || (selfContainerActive() && isContainerSignal(o.detail))) return;
        triggerSensor(SignalType.SOUND, locatedSubject(source), "Suspicious Sound: " + shortId(o.detail), 14, 3000L);
    }

    private void inspectEntitySound(Observation o) {
        if (!VanishText.suspiciousSound(o.detail)) return;
        Entity entity = mc.world.getEntityById(o.entityId);
        if (entity instanceof PlayerEntity player && player != mc.player && player.isInvisible()
            && player.squaredDistanceTo(mc.player) <= sensorRangeSq() && realPlayer(player)) {
            triggerSensor(SignalType.SOUND, player, "Suspicious Sound: invisible source", 16, 3000L);
        }
    }

    private void inspectParticle(Observation o) {
        if (!VanishText.suspiciousParticle(o.detail)) return;
        Vec3d source = o.position();
        long now = System.currentTimeMillis();
        if (!nearPlayer(source) || hasVisibleCause(source) || isExplosionRelated(source)
            || now - lastLocalActionMs < 900L || hasAmbientParticleSource(source, o.detail)) return;
        String particle = shortId(o.detail);
        boolean weak = particle.equals("block") || particle.contains("smoke");
        if (weak && nearSelf(source, 6.25)) return;
        if (weak && !particleBurstReady(particle, now)) return;
        triggerSensor(SignalType.PARTICLE, locatedSubject(source), "Ghost Particle: " + particle, 16, 3000L);
    }

    private boolean villagerToggledDoor(String blockId, Vec3d source) {
        String path = VanishText.path(blockId);
        boolean doorLike = (path.contains("door") && !path.contains("trapdoor")) || path.contains("fence_gate");
        if (!doorLike || source == null || mc.world == null) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() == EntityType.VILLAGER && posOf(entity).squaredDistanceTo(source) <= 9.0) return true;
        }
        return false;
    }

    private boolean recentlySelfPlaced(Vec3d source) {
        if (source == null) return false;
        Long until = selfPlacedBlocks.get(BlockPos.ofFloored(source).asLong());
        return until != null && until > System.currentTimeMillis();
    }

    private void rememberExplosion(Observation o) {
        recentExplosions.addLast(new ExplosionEvent(o.position(), EXPLOSION_RADIUS, System.currentTimeMillis()));
        while (recentExplosions.size() > 8) recentExplosions.removeFirst();
    }

    private boolean particleBurstReady(String particle, long now) {
        Deque<Long> burst = weakParticleBursts.computeIfAbsent(particle, k -> new ArrayDeque<>());
        burst.addLast(now);
        while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
        if (burst.size() < 2) return false;
        burst.clear();
        return true;
    }

    // ================= signals + CRITICAL scoring =================

    private void triggerSensor(SignalType type, String where, String reason, int weight, long cooldownMs) {
        addSignal(type, where, null, reason, weight, cooldownMs);
    }

    private void triggerSensor(SignalType type, PlayerEntity player, String reason, int weight, long cooldownMs) {
        addSignal(type, profileName(player), regionProof(player.getUuid()), reason, weight, cooldownMs);
    }

    private void addSignal(SignalType type, String subject, RegionProof proof, String reason, int weight, long cooldownMs) {
        long now = System.currentTimeMillis();
        String cooldownKey = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
        long last = signalCooldowns.getOrDefault(cooldownKey, 0L);
        if (now - last < cooldownMs) return;
        signalCooldowns.put(cooldownKey, now);
        signals.addLast(new Signal(type, subject, proof, reason, weight, now));
        upsertDetection("signal:" + cooldownKey, subject, reason, weight, now + DETECTION_TTL_MS);
        announceTrigger(cooldownKey, subject, proof != null, reason);
        evaluateCritical();
    }

    private void evaluateCritical() {
        if (!criticalEnabled.get()) return;
        long now = System.currentTimeMillis();
        pruneSignals(now);
        EnumMap<SignalType, Integer> strongest = new EnumMap<>(SignalType.class);
        Signal watcher = null;
        for (Signal s : signals) {
            strongest.merge(s.type, s.weight, Math::max);
            if (s.proof != null) watcher = s;
        }
        int score = strongest.values().stream().mapToInt(Integer::intValue).sum();
        if (watcher == null || strongest.size() < 2 || score < criticalScore.get()) return;
        if (now - lastCriticalMs < CRITICAL_COOLDOWN_MS) return;
        watchingYou(watcher.subject, watcher.proof);
    }

    private void watchingYou(String name, RegionProof proof) {
        long now = System.currentTimeMillis();
        lastCriticalMs = now;
        criticalUntilMs = now + 7000L;
        criticalWatcher = name;
        pendingChime = true;
        if (chatAlerts.get()) {
            String where = proof.distance() >= 0 ? " (" + proof.distance() + "m away)" : "";
            warning("CRITICAL: %s is watching you%s", name, where);
        }
    }

    private void playPendingChime() {
        if (!pendingChime) return;
        pendingChime = false;
        if (!chime.get() || mc.player == null) return;
        mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    // ================= chat =================

    private void news(String name, String what, String detail) {
        if (!chatAlerts.get()) return;
        if (detail == null || detail.isEmpty()) info("%s %s", name, what);
        else info("%s %s (%s)", name, what, detail);
    }

    private void announceGamemode(UUID uuid, String gameModeName) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;
        KnownPlayer known = knownPlayers.get(uuid);
        if (known == null || !isStaffName(known.name)) return;
        if (GameMode.SPECTATOR.name().equals(gameModeName)) {
            if (credibleSubject(uuid, known.name)) {
                reportHidden(uuid, known.name, true, "Vanish Event: switched to spectator", "", 100);
                return;
            }
        } else if (endSpell(known.name, true, 0L)) {
            return;
        }
        if (chatAlerts.get()) info("%s switched to %s", known.name, gameModeName.toLowerCase(Locale.ROOT));
    }

    private void announceTrigger(String eventKey, String subject, boolean named, String reason) {
        if (!chatAlerts.get()) return;
        long now = System.currentTimeMillis();
        Long last = announceCooldowns.get(eventKey);
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MS) return;
        while (!announceTimes.isEmpty() && now - announceTimes.peekFirst() > ANNOUNCE_WINDOW_MS) announceTimes.removeFirst();
        if (announceTimes.size() >= MAX_ANNOUNCE_PER_WINDOW) return;
        announceCooldowns.put(eventKey, now);
        announceTimes.addLast(now);
        boolean located = subject != null && !subject.isBlank() && !"Unknown".equalsIgnoreCase(subject)
            && !"CRITICAL".equalsIgnoreCase(subject);
        if (located) warning("%s (%s)", reason, subject);
        else warning("%s", reason);
    }

    // ================= detections + HUD =================

    private void upsertDetection(String key, String name, String reason, int score, long expiresAt) {
        Detection existing = detections.get(key);
        if (existing == null) {
            detections.put(key, new Detection(name, reason, score, expiresAt));
            return;
        }
        existing.name = name;
        existing.expiresAt = Math.max(existing.expiresAt, expiresAt);
        if (score >= existing.score) {
            existing.reason = reason;
            existing.score = score;
        }
    }

    private static boolean detectionWorthShowing(Detection d) {
        if (d == null) return false;
        String name = d.name == null ? "" : d.name.trim();
        return !name.isBlank() && !"Unknown".equalsIgnoreCase(name) && !"You".equalsIgnoreCase(name)
            && !"CRITICAL".equalsIgnoreCase(name);
    }

    private static String hudTag(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (lower.startsWith("vanish event")) return "VANISH";
        if (lower.startsWith("invisible entity")) return "INVIS";
        if (lower.startsWith("suspicious sound")) return "SOUND";
        if (lower.startsWith("ghost particle")) return "PARTICLE";
        return "WATCH";
    }

    private static String shortName(String name) {
        String shown = name == null ? "" : name.trim();
        return shown.length() <= 16 ? shown : shown.substring(0, 16);
    }

    public record HudEntry(String name, String tag, String value) {
    }

    /** Rows for the HUD element: CRITICAL watcher first, then the strongest live detections (max 4). */
    public List<HudEntry> hudEntries() {
        long now = System.currentTimeMillis();
        List<HudEntry> out = new ArrayList<>();
        if (now < criticalUntilMs) out.add(new HudEntry("CRITICAL", "ALERT", shortName(criticalWatcher)));
        Set<String> seen = new HashSet<>();
        detections.values().stream()
            .filter(d -> d.expiresAt > now)
            .filter(AntiVanish::detectionWorthShowing)
            .sorted(Comparator.comparingInt((Detection d) -> d.score).reversed()
                .thenComparing(d -> d.name, String.CASE_INSENSITIVE_ORDER))
            .map(d -> new HudEntry(d.name, hudTag(d.reason), shortName(d.name)))
            .filter(e -> seen.add(e.tag() + "\u0000" + e.value()))
            .limit(4)
            .forEach(out::add);
        return List.copyOf(out);
    }

    // ================= misc helpers =================

    private void pruneState() {
        long now = System.currentTimeMillis();
        detections.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        signalCooldowns.entrySet().removeIf(e -> now - e.getValue() > 60000L);
        announceCooldowns.entrySet().removeIf(e -> now - e.getValue() > 60000L);
        selfPlacedBlocks.entrySet().removeIf(e -> e.getValue() <= now);
        confirmedDepartures.entrySet().removeIf(e -> e.getValue() <= now);
        automatedMechanisms.entrySet().removeIf(e -> e.getValue() <= now);
        sightings.entrySet().removeIf(e -> now - e.getValue() > SIGHTING_TTL_MS);
        weakParticleBursts.values().removeIf(burst -> {
            while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
            return burst.isEmpty();
        });
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        pruneSignals(now);
    }

    private void pruneSignals(long now) {
        while (!signals.isEmpty() && now - signals.peekFirst().timeMs > SIGNAL_WINDOW_MS) signals.removeFirst();
    }

    private static Vec3d posOf(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    private boolean nearPlayer(Vec3d source) {
        return source != null && source.squaredDistanceTo(posOf(mc.player)) <= sensorRangeSq();
    }

    private boolean nearSelf(Vec3d source, double maxDistSq) {
        return source != null && mc.player != null && source.squaredDistanceTo(posOf(mc.player)) < maxDistSq;
    }

    private boolean selfContainerActive() {
        return System.currentTimeMillis() - lastContainerActivityMs < CONTAINER_SELF_GRACE_MS;
    }

    private static boolean isContainerSignal(String id) {
        String path = VanishText.path(id);
        return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
    }

    private String locatedSubject(Vec3d source) {
        if (source == null || mc.player == null) return "nearby";
        Vec3d me = posOf(mc.player);
        double dx = source.x - me.x;
        double dy = source.y - me.y;
        double dz = source.z - me.z;
        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        String dir = compass(dx, dz);
        return dir.isEmpty() ? dist + "m" : dist + "m " + dir;
    }

    private static String compass(double dx, double dz) {
        String ns = dz < -1.0 ? "N" : (dz > 1.0 ? "S" : "");
        String ew = dx > 1.0 ? "E" : (dx < -1.0 ? "W" : "");
        return ns + ew;
    }

    private boolean hasVisibleCause(Vec3d source) {
        if (source == null) return true;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            if (entity instanceof PlayerEntity player) {
                if (!player.isInvisible() && posOf(entity).squaredDistanceTo(source) <= 16.0) return true;
            } else if (entity instanceof ProjectileEntity && posOf(entity).squaredDistanceTo(source) <= 16.0) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplosionRelated(Vec3d source) {
        if (source == null) return false;
        long now = System.currentTimeMillis();
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        for (ExplosionEvent e : recentExplosions) {
            if (e.center.squaredDistanceTo(source) <= e.radius * e.radius) return true;
        }
        return false;
    }

    private boolean hasAmbientParticleSource(Vec3d source, String particleId) {
        if (source == null || !shortId(particleId).contains("smoke")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock());
                    String path = shortId(id == null ? "" : id.toString());
                    if (path.contains("campfire") || path.contains("furnace") || path.contains("smoker")
                        || path.contains("torch") || path.contains("fire") || path.contains("candle")
                        || path.contains("respawn_anchor")) return true;
                }
            }
        }
        return false;
    }

    private boolean isPoweredMechanism(Vec3d source, String soundId) {
        String path = shortId(soundId);
        if (!path.contains("door") && !path.contains("trapdoor")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        long now = System.currentTimeMillis();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.add(0, dy, 0);
            var state = mc.world.getBlockState(pos);
            long key = pos.asLong();
            boolean powered = state.contains(Properties.POWERED) && state.get(Properties.POWERED);
            if (powered || mc.world.isReceivingRedstonePower(pos)) {
                automatedMechanisms.put(key, now + 5000L);
                return true;
            }
            if (automatedMechanisms.getOrDefault(key, 0L) > now) return true;
        }
        return false;
    }

    private double sensorRangeSq() {
        double r = sensorRange.get();
        return r * r;
    }

    private static String shortId(String id) {
        return VanishText.path(id);
    }

    /** Detects a server/world switch so state from the old one is dropped. */
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

    private void resetRuntime() {
        observations.clear();
        knownPlayers.clear();
        detections.clear();
        signalCooldowns.clear();
        announceCooldowns.clear();
        announceTimes.clear();
        selfPlacedBlocks.clear();
        confirmedDepartures.clear();
        automatedMechanisms.clear();
        weakParticleBursts.clear();
        signals.clear();
        recentExplosions.clear();
        pendingVanishes.clear();
        hiddenSpells.clear();
        recentMessages.clear();
        listedSinceMs.clear();
        sightings.clear();
        completionRequestIds.clear();
        pendingCompletionNames = null;
        serverSendsLeaveMessages = false;
        nextCompletionId = 30000;
        tickCounter = 0;
        lastLocalActionMs = 0L;
        lastContainerActivityMs = 0L;
        lastCriticalMs = 0L;
        criticalUntilMs = 0L;
        criticalWatcher = "";
        pendingChime = false;
        lastWorld = null;
        lastPlayer = null;
        lastBrand = null;
    }

    // ================= data types =================

    private enum ObservationType {
        TAB_REMOVE, TAB_HIDE, PLAYER_LEFT, SYSTEM_CHAT, ENTITY_METADATA,
        POSITIONAL_SOUND, ENTITY_SOUND, PARTICLE, EXPLOSION, GAMEMODE
    }

    private enum SignalType {VANISH, INVISIBLE, PARTICLE, SOUND}

    private record Observation(ObservationType type, UUID profileId, int entityId, double x, double y, double z, String detail) {
        static Observation tabRemove(UUID id) {
            return new Observation(ObservationType.TAB_REMOVE, id, -1, 0, 0, 0, "");
        }

        static Observation tabHide(UUID id) {
            return new Observation(ObservationType.TAB_HIDE, id, -1, 0, 0, 0, "");
        }

        static Observation gamemode(UUID id, String mode) {
            return new Observation(ObservationType.GAMEMODE, id, -1, 0, 0, 0, mode);
        }

        static Observation playerLeft(String name) {
            return new Observation(ObservationType.PLAYER_LEFT, null, -1, 0, 0, 0, name);
        }

        static Observation systemChat(String text) {
            return new Observation(ObservationType.SYSTEM_CHAT, null, -1, 0, 0, 0, text);
        }

        static Observation entityMetadata(int id) {
            return new Observation(ObservationType.ENTITY_METADATA, null, id, 0, 0, 0, "");
        }

        static Observation explosion(Vec3d center) {
            return new Observation(ObservationType.EXPLOSION, null, -1, center.x, center.y, center.z, "");
        }

        static Observation entitySound(int id, String sound) {
            return new Observation(ObservationType.ENTITY_SOUND, null, id, 0, 0, 0, sound);
        }

        static Observation position(ObservationType type, double x, double y, double z, String detail) {
            return new Observation(type, null, -1, x, y, z, detail);
        }

        Vec3d position() {
            return new Vec3d(x, y, z);
        }
    }

    private static final class Detection {
        String name;
        String reason;
        int score;
        long expiresAt;

        Detection(String name, String reason, int score, long expiresAt) {
            this.name = name;
            this.reason = reason;
            this.score = score;
            this.expiresAt = expiresAt;
        }
    }

    private record KnownPlayer(UUID uuid, String name) {
    }

    private record PendingVanish(UUID uuid, String name, int dueTick) {
    }

    private record RegionProof(UUID id, int distance) {
    }

    private record Signal(SignalType type, String subject, RegionProof proof, String reason, int weight, long timeMs) {
    }

    private record RecentMessage(String text, long atMs) {
    }

    private record ExplosionEvent(Vec3d center, double radius, long timeMs) {
    }

    private static final class HiddenSpell {
        final UUID uuid;
        final String name;
        final boolean spectator;
        final String detail;
        final RegionProof proof;
        final int sinceTick;
        final long sinceMs = System.currentTimeMillis();
        boolean told;
        boolean watched;

        HiddenSpell(UUID uuid, String name, boolean spectator, String detail, RegionProof proof, int sinceTick) {
            this.uuid = uuid;
            this.name = name;
            this.spectator = spectator;
            this.detail = detail;
            this.proof = proof;
            this.sinceTick = sinceTick;
        }
    }
}
