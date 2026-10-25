package com.example.allayaddon.utils;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Фільтр кольору шалкер-бокса для налаштувань.
 * Any — підходить будь-який шалкер; Undyed — звичайний фіолетовий (недофарбований).
 */
public enum ShulkerColor {
    Any(null),
    Undyed(Items.SHULKER_BOX),
    White(Items.WHITE_SHULKER_BOX),
    Orange(Items.ORANGE_SHULKER_BOX),
    Magenta(Items.MAGENTA_SHULKER_BOX),
    LightBlue(Items.LIGHT_BLUE_SHULKER_BOX),
    Yellow(Items.YELLOW_SHULKER_BOX),
    Lime(Items.LIME_SHULKER_BOX),
    Pink(Items.PINK_SHULKER_BOX),
    Gray(Items.GRAY_SHULKER_BOX),
    LightGray(Items.LIGHT_GRAY_SHULKER_BOX),
    Cyan(Items.CYAN_SHULKER_BOX),
    Purple(Items.PURPLE_SHULKER_BOX),
    Blue(Items.BLUE_SHULKER_BOX),
    Brown(Items.BROWN_SHULKER_BOX),
    Green(Items.GREEN_SHULKER_BOX),
    Red(Items.RED_SHULKER_BOX),
    Black(Items.BLACK_SHULKER_BOX);

    private final Item item;

    ShulkerColor(Item item) {
        this.item = item;
    }

    /** true, якщо стек є шалкером і відповідає обраному кольору. */
    public boolean matches(ItemStack stack) {
        if (!AllayUtils.isShulkerBox(stack)) return false;
        return item == null || stack.isOf(item);
    }
}
