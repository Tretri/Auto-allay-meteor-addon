package com.example.allayaddon.modules;

import com.example.allayaddon.AllayAddon;
import com.example.allayaddon.utils.AllayUtils;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

/**
 * Б'є лише ейлеїв, які тримають шалкер. Тримає ціль (target lock): поки ціль жива,
 * в радіусі і з шалкером у руці — модуль не перемикається на інших ейлеїв.
 */
public class AllayKillAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWeapon = settings.createGroup("Weapon");

    // General

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to attack an allay.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> hitDelay = sgGeneral.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("Minimum ticks between hits (20 ticks = 1 second, so CPS = 20 / (delay + 1)).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> respectCooldown = sgGeneral.add(new BoolSetting.Builder()
        .name("respect-cooldown")
        .description("Wait for the vanilla attack cooldown to fully recharge before hitting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at the target before hitting.")
        .defaultValue(false)
        .build()
    );

    // Weapon

    private final Setting<Integer> minLooting = sgWeapon.add(new IntSetting.Builder()
        .name("min-looting")
        .description("Only attack with an item that has at least this Looting level; the module switches to one in your hotbar. 0 = use whatever you hold.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 5)
        .build()
    );

    private AllayEntity target;
    private int hitTimer = Integer.MAX_VALUE / 2;
    private int warnTimer;

    public AllayKillAura() {
        super(AllayAddon.CATEGORY, "allay-kill-aura", "Attacks only allays that hold a shulker box, one target at a time.");
    }

    @Override
    public void onActivate() {
        target = null;
        warnTimer = 0;
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        target = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (hitTimer < Integer.MAX_VALUE / 2) hitTimer++;
        if (warnTimer > 0) warnTimer--;

        // Target lock: поточна ціль залишається, доки вона валідна.
        if (target != null && !isValidTarget(target)) target = null;
        if (target == null) target = findNewTarget();
        if (target == null) return;

        if (!equipWeapon()) return;
        if (hitTimer < hitDelay.get()) return;
        if (respectCooldown.get() && mc.player.getAttackCooldownProgress(0.5f) < 1f) return;

        final AllayEntity victim = target;
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(victim), Rotations.getPitch(victim), () -> attack(victim));
        } else {
            attack(victim);
        }
    }

    private void attack(AllayEntity victim) {
        if (mc.player == null || mc.interactionManager == null) return;

        mc.interactionManager.attackEntity(mc.player, victim);
        mc.player.swingHand(Hand.MAIN_HAND);
        hitTimer = 0;
    }

    /** Ціль валідна, поки вона жива, не видалена зі світу, в радіусі й досі тримає шалкер. */
    private boolean isValidTarget(AllayEntity allay) {
        return !allay.isRemoved()
            && allay.isAlive()
            && AllayUtils.isInRange(allay, range.get())
            && AllayUtils.holdsShulkerBox(allay);
    }

    /** Найближчий ейлей із шалкером у радіусі (список уже відсортований за відстанню). */
    private AllayEntity findNewTarget() {
        for (AllayEntity allay : AllayUtils.getAllaysInRange(range.get())) {
            if (AllayUtils.holdsShulkerBox(allay)) return allay;
        }
        return null;
    }

    /** Переконується, що в руці предмет з потрібним рівнем Looting; за потреби свапає хотбар. */
    private boolean equipWeapon() {
        int min = minLooting.get();
        if (min <= 0) return true;
        if (lootingLevel(mc.player.getMainHandStack()) >= min) return true;

        for (int i = 0; i < 9; i++) {
            if (lootingLevel(mc.player.getInventory().getStack(i)) >= min) {
                InvUtils.swap(i, false);
                return true;
            }
        }

        if (warnTimer == 0) {
            warning("No hotbar item with Looting %d found.", min);
            warnTimer = 100;
        }
        return false;
    }

    private static int lootingLevel(ItemStack stack) {
        return Utils.getEnchantmentLevel(stack, Enchantments.LOOTING);
    }

    @Override
    public String getInfoString() {
        return target != null ? String.format("%.0f HP", target.getHealth()) : null;
    }
}
