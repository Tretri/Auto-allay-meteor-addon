package com.example.allayaddon.modules;

import com.example.allayaddon.AllayAddon;
import com.example.allayaddon.utils.AllayUtils;
import com.example.allayaddon.utils.ShulkerColor;
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

import java.util.List;
import java.util.UUID;

/**
 * Роздає шалкери порожнім ейлеям. Якщо ейлей тримає «неправильний» предмет, спочатку
 * забирає його (клік порожньою рукою, як у ванілі) і одразу дає потрібний шалкер.
 * Шалкери беруться тільки з хотбара.
 *
 * Рішення приймаються лише за станом, який підтвердив сервер (див. AllayUtils), а після кожної дії
 * з ейлеєм модуль чекає, поки відповідь сервера дійде (час залежить від вашого пінгу).
 */
public class AllayAutoGiver extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLag = settings.createGroup("Lag");

    // General

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance from your eyes to the allay's hitbox.")
        .defaultValue(3.5)
        .min(0)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<ShulkerColor> color = sgGeneral.add(new EnumSetting.Builder<ShulkerColor>()
        .name("shulker-color")
        .description("Which shulker box to hand out. Any = every colour is accepted.")
        .defaultValue(ShulkerColor.Any)
        .build()
    );

    private final Setting<Boolean> replaceWrongItems = sgGeneral.add(new BoolSetting.Builder()
        .name("replace-wrong-items")
        .description("Take away anything the allay holds that does not match the filter, then give the right shulker. Needs an empty hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between two actions on any allay.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Silently look at the allay before clicking, like a real player does. Some servers ignore clicks on things you are not facing.")
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

    private int timer;
    private int warnTimer;
    private int statusTimer;

    public AllayAutoGiver() {
        super(AllayAddon.CATEGORY, "allay-auto-giver", "Gives shulker boxes to empty allays and swaps out wrong held items.");
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
        AllayUtils.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;

        if (warnTimer > 0) warnTimer--;
        AllayUtils.pollPending();

        List<AllayEntity> allays = AllayUtils.getAllaysInReach(range.get());

        if (logActions.get() && ++statusTimer >= 100) {
            statusTimer = 0;
            reportStatus(allays);
        }

        if (timer > 0) {
            timer--;
            return;
        }
        if (allays.isEmpty()) return;

        ShulkerColor wanted = color.get();
        long window = AllayUtils.lagWindowMs(actionDelay.get(), compensatePing.get());

        for (AllayEntity allay : allays) {
            UUID id = allay.getUuid();
            if (AllayUtils.isBusy(id)) continue; // чекаємо відповіді сервера на попередню дію

            ItemStack held = allay.getMainHandStack();
            boolean empty = held.isEmpty();

            if (!empty && wanted.matches(held)) continue;      // уже тримає потрібний шалкер
            if (!empty && !replaceWrongItems.get()) continue;  // чуже в руці, а заміна вимкнена

            int shulkerSlot = AllayUtils.findHotbarSlot(wanted::matches);
            if (shulkerSlot == -1) {
                // Усі підходящі шалкери можуть бути «в польоті» — тоді просто чекаємо.
                if (!AllayUtils.hasHotbarItem(wanted::matches)) {
                    warnOnce("No matching shulker box (" + wanted.name() + ") in your hotbar.");
                }
                return;
            }

            // Чуже в руці: спочатку забираємо порожньою рукою, потім даємо шалкер (в одному тіку).
            int freedSlot = -1;
            if (!empty) {
                freedSlot = AllayUtils.findEmptyHotbarSlot();
                if (freedSlot == -1) {
                    warnOnce("No empty hotbar slot to take the item back from an allay.");
                    return;
                }
                AllayUtils.reserveSlot(freedSlot, window);
            }

            AllayUtils.reserveSlot(shulkerSlot, window); // шалкер не стакається: слот скоро спорожніє
            AllayUtils.markBusy(id, window);

            int[] slots = freedSlot == -1 ? new int[]{shulkerSlot} : new int[]{freedSlot, shulkerSlot};
            AllayUtils.clickSequence(allay, rotate.get(), slots);
            timer = delay.get();

            if (logActions.get()) {
                if (empty) {
                    info("Gave a shulker to allay #%d (sent).", allay.getId());
                } else {
                    info("Replaced %s on allay #%d (sent).", held.getName().getString(), allay.getId());
                }
                AllayUtils.expectHand(id, wanted::matches, "Shulker for allay #" + allay.getId(),
                    window * 2 + 4000, this::say);
            }
            return; // одна дія за цикл
        }
    }

    private void reportStatus(List<AllayEntity> allays) {
        ShulkerColor wanted = color.get();
        int emptyHanded = 0;
        int correct = 0;
        int wrong = 0;
        for (AllayEntity allay : allays) {
            ItemStack held = allay.getMainHandStack();
            if (held.isEmpty()) emptyHanded++;
            else if (wanted.matches(held)) correct++;
            else wrong++;
        }

        info("Status: %d allay(s) in reach: %d empty-handed, %d holding the right shulker, %d holding something else; shulkers in hotbar: %d; ping %d ms.",
            allays.size(), emptyHanded, correct, wrong,
            AllayUtils.countHotbar(wanted::matches), AllayUtils.getPingMs());
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
        return color.get().name();
    }
}
