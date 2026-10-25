package com.example.allayaddon.utils;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Спільні допоміжні методи для всіх модулів.
 *
 * Кліки по ейлеях відправляються пакетами БЕЗ клієнтського передбачення: ванільний
 * interactEntity() ще й сам виконує взаємодію локально, і клієнт «бачить» результат,
 * якого сервер не підтвердив. Тому все, що видно в руці ейлея, підтверджено сервером.
 * Для діагностики є трекер підтверджень (expectHand / pollPending).
 */
public final class AllayUtils {
    private AllayUtils() {}

    /** Верхня межа очікування між діями з одним ейлеєм (щоб фальшивий пінг у табі не «вбив» модуль). */
    private static final long MAX_WINDOW_MS = 12_000;

    /** Ейлей, з яким щойно щось робили: чекаємо реакції сервера (UUID -> реальний час, мс). */
    private static final Map<UUID, Long> BUSY_ALLAYS = new HashMap<>();

    /** Слоти хотбара, чий вміст ще «в польоті» (слот -> реальний час, мс). */
    private static final Map<Integer, Long> RESERVED_SLOTS = new HashMap<>();

    /** Дії, підтвердження яких від сервера ми чекаємо (для діагностики в чаті). */
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(UUID id, Predicate<ItemStack> expected, String what, long sentAt, long timeoutMs, Consumer<String> out) {}

    // ───────────────────────── Предмети / ейлеї ─────────────────────────

    /** Чи є предмет шалкер-боксом будь-якого кольору. */
    public static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
            && stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Чи тримає сутність шалкер у головній або другій руці. */
    public static boolean holdsShulkerBox(LivingEntity entity) {
        return isShulkerBox(entity.getMainHandStack()) || isShulkerBox(entity.getOffHandStack());
    }

    /** Проста відстань «ноги → ноги». Використовує KillAura. */
    public static boolean isInRange(Entity entity, double range) {
        return mc.player != null && mc.player.squaredDistanceTo(entity) <= range * range;
    }

    /** Живі ейлеї в радіусі (ноги → ноги), від найближчого. Використовує KillAura. */
    public static List<AllayEntity> getAllaysInRange(double range) {
        List<AllayEntity> result = new ArrayList<>();
        if (mc.player == null || mc.world == null) return result;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof AllayEntity allay && allay.isAlive() && isInRange(allay, range)) {
                result.add(allay);
            }
        }

        result.sort(Comparator.comparingDouble((AllayEntity a) -> mc.player.squaredDistanceTo(a)));
        return result;
    }

    /** Відстань від очей гравця до найближчої точки хітбокса сутності (так сервер міряє дальність). */
    public static double distanceToHitbox(Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        Box box = entity.getBoundingBox();

        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Живі ейлеї в зоні досяжності (очі → хітбокс), від найближчого. Для Breeder/AutoGiver. */
    public static List<AllayEntity> getAllaysInReach(double reach) {
        List<AllayEntity> result = new ArrayList<>();
        if (mc.player == null || mc.world == null) return result;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof AllayEntity allay && allay.isAlive() && distanceToHitbox(allay) <= reach) {
                result.add(allay);
            }
        }

        result.sort(Comparator.comparingDouble((AllayEntity a) -> distanceToHitbox(a)));
        return result;
    }

    private static AllayEntity findAllay(UUID id) {
        if (mc.world == null) return null;
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof AllayEntity allay && allay.getUuid().equals(id)) return allay;
        }
        return null;
    }

    // ───────────────────────── Лаги ─────────────────────────

    public static int getPingMs() {
        if (mc.player == null || mc.getNetworkHandler() == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : entry.getLatency();
    }

    /** Скільки мс чекати реакції сервера: базові тіки + (за потреби) 1.5 × пінг, але не більше 12 с. */
    public static long lagWindowMs(int baseTicks, boolean compensate) {
        long ms = baseTicks * 50L;
        if (compensate) ms += (long) (getPingMs() * 1.5);
        return Math.min(ms, MAX_WINDOW_MS);
    }

    public static boolean isBusy(UUID id) {
        Long until = BUSY_ALLAYS.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            BUSY_ALLAYS.remove(id);
            return false;
        }
        return true;
    }

    public static void markBusy(UUID id, long ms) {
        BUSY_ALLAYS.put(id, System.currentTimeMillis() + ms);
    }

    public static boolean isSlotReserved(int slot) {
        Long until = RESERVED_SLOTS.get(slot);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            RESERVED_SLOTS.remove(slot);
            return false;
        }
        return true;
    }

    public static void reserveSlot(int slot, long ms) {
        RESERVED_SLOTS.put(slot, System.currentTimeMillis() + ms);
    }

    /** Скидає весь стан очікування (виклик при вході у світ/на сервер). */
    public static void reset() {
        BUSY_ALLAYS.clear();
        RESERVED_SLOTS.clear();
        PENDING.clear();
    }

    // ───────────────────────── Діагностика: чи підтвердив сервер ─────────────────────────

    /** Запам'ятати, що за очікуванням рука ейлея невдовзі має відповідати умові. */
    public static void expectHand(UUID id, Predicate<ItemStack> expected, String what, long timeoutMs, Consumer<String> out) {
        PENDING.add(new Pending(id, expected, what, System.currentTimeMillis(), timeoutMs, out));
    }

    /** Викликається щотіку: пише в чат, коли сервер підтвердив дію або коли час очікування вийшов. */
    public static void pollPending() {
        if (PENDING.isEmpty() || mc.world == null) return;

        long now = System.currentTimeMillis();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            long elapsed = now - p.sentAt();
            AllayEntity allay = findAllay(p.id());

            if (allay != null && p.expected().test(allay.getMainHandStack())) {
                p.out().accept(String.format("%s: server CONFIRMED after %.1f s (ping %d ms).", p.what(), elapsed / 1000.0, getPingMs()));
                it.remove();
            } else if (elapsed > p.timeoutMs()) {
                p.out().accept(String.format("%s: NO confirmation after %.1f s (ping %d ms). The server ignored or rejected the click.", p.what(), elapsed / 1000.0, getPingMs()));
                it.remove();
            }
        }
    }

    // ───────────────────────── Інвентар ─────────────────────────

    /** Перший незарезервований слот хотбара (0–8), предмет у якому підходить під фільтр, або -1. */
    public static int findHotbarSlot(Predicate<ItemStack> filter) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isSlotReserved(i)) continue;
            if (filter.test(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /** Чи є в хотбарі підходящий предмет, навіть якщо його слот зараз зарезервований. */
    public static boolean hasHotbarItem(Predicate<ItemStack> filter) {
        return countHotbar(filter) > 0;
    }

    /** Сумарна кількість предметів у хотбарі, що підходять під фільтр. */
    public static int countHotbar(Predicate<ItemStack> filter) {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (filter.test(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Перший порожній і незарезервований слот хотбара або -1. */
    public static int findEmptyHotbarSlot() {
        return findHotbarSlot(ItemStack::isEmpty);
    }

    // ───────────────────────── Взаємодія ─────────────────────────

    /**
     * Клікає по сутності по черзі предметами зі слотів хотбара (порожній слот = порожня рука).
     * Перед цим за потреби беззвучно повертається обличчям до сутності (як це робить справжній гравець).
     */
    public static void clickSequence(Entity entity, boolean rotate, int... slots) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        Runnable action = () -> {
            for (int slot : slots) clickWithSlot(entity, slot);
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate) Rotations.rotate(Rotations.getYaw(entity), Rotations.getPitch(entity), action);
        else action.run();
    }

    /**
     * Повторює те, що робить ванільний клієнт при правому кліку по сутності:
     * «вибрати слот» → INTERACT_AT → INTERACT → «повернути колишній слот».
     * Клієнтський вибраний слот не чіпаємо, тому інші модулі (KillAura тощо) не збиваються.
     */
    private static void clickWithSlot(Entity entity, int slot) {
        int previous = mc.player.getInventory().getSelectedSlot();
        boolean sneaking = mc.player.isSneaking();
        Vec3d hitPos = new Vec3d(0, entity.getHeight() / 2.0, 0); // відносно позиції сутності

        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.interactAt(entity, sneaking, Hand.MAIN_HAND, hitPos));
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.interact(entity, sneaking, Hand.MAIN_HAND));
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(previous));
    }
}
