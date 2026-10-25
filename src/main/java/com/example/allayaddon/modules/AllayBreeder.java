package com.example.allayaddon.modules;

import com.example.allayaddon.AllayAddon;
import com.example.allayaddon.utils.AllayUtils;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Годує ейлеїв аметистовими осколками і забирає осколок назад, якщо ейлей його «проковтнув».
 *
 * Механіка (ванільна взаємодія з ейлеєм):
 *  - якщо ейлей танцює і КД минув — осколок витрачається, ейлей розмножується;
 *  - якщо КД ще йде, а рука ейлея порожня — ейлей просто бере осколок у руку;
 *  - забрати предмет назад можна кліком ПОРОЖНЬОЮ головною рукою.
 *
 * Клієнт не бачить справжнього КД (AllayEntity#canDuplicate приватний), тому в режимі Cooldown
 * час годування запам'ятовується за UUID у світових (серверних) тіках.
 */
public class AllayBreeder extends Module {
    public enum Mode {
        /** Годує лише тоді, коли минув КД (облік за UUID). */
        Cooldown,
        /** Постійно дає і забирає осколок, не рахуючи КД: «а раптом уже минув». */
        Constant
    }

    public enum UnknownAllayPolicy {
        /** Ейлей, якого ми ще не годували, вважається готовим одразу. */
        AssumeReady,
        /** Вперше побачений ейлей чекає повний КД. */
        WaitFullCooldown
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLag = settings.createGroup("Lag");
    private final SettingGroup sgCooldown = settings.createGroup("Cooldown");

    // General

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Cooldown: feed only when the cooldown is over. Constant: keep giving and taking the shard back, ignoring the cooldown.")
        .defaultValue(Mode.Cooldown)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance from your eyes to the allay's hitbox.")
        .defaultValue(3.5)
        .min(0)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between two actions on any allay.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Silently look at the allay before clicking, like a real player does. Some servers ignore clicks on things you are not facing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyDancing = sgGeneral.add(new BoolSetting.Builder()
        .name("only-dancing")
        .description("Only feed dancing allays (a jukebox must be playing nearby). Vanilla allays only duplicate while dancing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> takeBack = sgGeneral.add(new BoolSetting.Builder()
        .name("take-back-shards")
        .description("Take an amethyst shard back from an allay that kept it in its hand. Also runs right after the module is enabled. Needs an empty hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logActions = sgGeneral.add(new BoolSetting.Builder()
        .name("log-actions")
        .description("Chat diagnostics: a status line every 5 seconds, every action, and whether the server confirmed it. Turn off when everything works.")
        .defaultValue(true)
        .build()
    );

    // Lag

    private final Setting<Integer> actionDelay = sgLag.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Minimum ticks before the same allay is touched again, so the module waits for the server's answer.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> compensatePing = sgLag.add(new BoolSetting.Builder()
        .name("compensate-ping")
        .description("Add 1.5x your ping (max 12 s in total) to the wait above. Recommended on a laggy server.")
        .defaultValue(true)
        .build()
    );

    // Cooldown

    private final Setting<Integer> cooldownTicks = sgCooldown.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Duplication cooldown in server ticks (vanilla 6000 = 5 minutes at 20 TPS). On a server with low TPS it lasts longer in real time.")
        .defaultValue(6000)
        .min(0)
        .sliderRange(0, 12000)
        .build()
    );

    private final Setting<Integer> cooldownMargin = sgCooldown.add(new IntSetting.Builder()
        .name("cooldown-margin")
        .description("Extra ticks added to the cooldown, because the client's world time is only periodically synced with the server.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 600)
        .build()
    );

    private final Setting<UnknownAllayPolicy> unknownPolicy = sgCooldown.add(new EnumSetting.Builder<UnknownAllayPolicy>()
        .name("unknown-allays")
        .description("Cooldown mode: what to do with allays this module has not fed yet.")
        .defaultValue(UnknownAllayPolicy.AssumeReady)
        .build()
    );

    private final Map<UUID, Long> lastFed = new HashMap<>();
    private int timer;
    private int pruneTimer;
    private int warnTimer;
    private int statusTimer;

    public AllayBreeder() {
        super(AllayAddon.CATEGORY, "allay-breeder", "Feeds amethyst shards to allays and takes them back when an allay keeps one.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        warnTimer = 0;
        statusTimer = 100; // статус одразу при вмиканні
        if (logActions.get()) {
            info("Enabled. Ping %d ms, wait per allay %.1f s.", AllayUtils.getPingMs(),
                AllayUtils.lagWindowMs(actionDelay.get(), compensatePing.get()) / 1000.0);
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        lastFed.clear();
        AllayUtils.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        long now = mc.world.getTime();

        if (warnTimer > 0) warnTimer--;
        if (++pruneTimer >= 200) {
            pruneTimer = 0;
            long maxAge = cooldownTicks.get() * 4L;
            lastFed.values().removeIf(time -> now - time > maxAge);
        }

        AllayUtils.pollPending();

        List<AllayEntity> allays = AllayUtils.getAllaysInReach(range.get());
        int shardSlot = AllayUtils.findHotbarSlot(stack -> stack.isOf(Items.AMETHYST_SHARD));

        if (logActions.get() && ++statusTimer >= 100) {
            statusTimer = 0;
            reportStatus(allays);
        }

        if (timer > 0) {
            timer--;
            return;
        }

        long window = AllayUtils.lagWindowMs(actionDelay.get(), compensatePing.get());

        for (AllayEntity allay : allays) {
            UUID id = allay.getUuid();
            if (AllayUtils.isBusy(id)) continue; // чекаємо відповіді сервера на попередню дію

            // 1) Ейлей тримає осколок — забираємо його назад порожньою рукою.
            if (takeBack.get() && allay.getMainHandStack().isOf(Items.AMETHYST_SHARD)) {
                int slot = AllayUtils.findEmptyHotbarSlot();
                if (slot == -1) {
                    warnOnce("No empty hotbar slot to take the shard back.");
                    continue;
                }

                AllayUtils.reserveSlot(slot, window);
                AllayUtils.markBusy(id, window);
                // Ейлей проковтнув осколок => КД ще йшов. Якщо ми його не годували — рахуємо від зараз.
                if (mode.get() == Mode.Cooldown) lastFed.putIfAbsent(id, now);

                AllayUtils.clickSequence(allay, rotate.get(), slot);
                timer = delay.get();

                if (logActions.get()) {
                    info("Took a shard back from allay #%d (sent).", allay.getId());
                    AllayUtils.expectHand(id, stack -> !stack.isOf(Items.AMETHYST_SHARD),
                        "Take-back from allay #" + allay.getId(), window * 2 + 4000, this::say);
                }
                return;
            }

            // 2) Даємо осколок.
            if (shardSlot == -1) continue;
            if (!canFeed(allay, now)) continue;

            ItemStack shards = mc.player.getInventory().getStack(shardSlot);
            // Останній осколок у стеку: слот ще «в польоті», поки сервер не підтвердить.
            if (shards.getCount() <= 1) AllayUtils.reserveSlot(shardSlot, window);
            AllayUtils.markBusy(id, window);
            if (mode.get() == Mode.Cooldown) lastFed.put(id, now);

            AllayUtils.clickSequence(allay, rotate.get(), shardSlot);
            timer = delay.get();

            if (logActions.get()) {
                info("Gave a shard to allay #%d (sent, dancing: %s).", allay.getId(), allay.isDancing());
            }
            return;
        }

        if (shardSlot == -1 && !AllayUtils.hasHotbarItem(stack -> stack.isOf(Items.AMETHYST_SHARD))) {
            warnOnce("No amethyst shards in your hotbar.");
        }
    }

    private void reportStatus(List<AllayEntity> allays) {
        int dancing = 0;
        int holdingShard = 0;
        for (AllayEntity allay : allays) {
            if (allay.isDancing()) dancing++;
            if (allay.getMainHandStack().isOf(Items.AMETHYST_SHARD)) holdingShard++;
        }

        info("Status: %d allay(s) in reach, %d dancing, %d holding a shard; shards in hotbar: %d; ping %d ms.",
            allays.size(), dancing, holdingShard,
            AllayUtils.countHotbar(stack -> stack.isOf(Items.AMETHYST_SHARD)), AllayUtils.getPingMs());
    }

    private boolean canFeed(AllayEntity allay, long now) {
        if (onlyDancing.get() && !allay.isDancing()) return false;
        if (mode.get() == Mode.Constant) return true;

        UUID id = allay.getUuid();
        Long last = lastFed.get(id);

        if (last == null) {
            if (unknownPolicy.get() == UnknownAllayPolicy.WaitFullCooldown) {
                lastFed.put(id, now); // відлік із першої зустрічі
                return false;
            }
            return true;
        }

        if (now < last) { // час світу «відкотився» після синхронізації з сервером
            lastFed.put(id, now);
            return false;
        }

        return now - last >= cooldownTicks.get() + cooldownMargin.get();
    }

    private void say(String message) {
        info("%s", message);
    }

    private void warnOnce(String message) {
        if (warnTimer > 0) return;
        warning(message);
        warnTimer = 100;
    }

    @Override
    public String getInfoString() {
        return mode.get().name();
    }
}
