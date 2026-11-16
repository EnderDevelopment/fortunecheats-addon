package com.enderdevelopment.marketplace;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

public final class ShopCatalog {
    public record Product(String id, String label, Item item, int buyPrice, int sellPrice, int stackSize) {}

    public static final List<Product> PRODUCTS = List.of(
        new Product("cobblestone", "Cobblestone", Items.COBBLESTONE, 2, 1, 64),
        new Product("oak_log", "Oak Log", Items.OAK_LOG, 8, 4, 32),
        new Product("coal", "Coal", Items.COAL, 12, 6, 32),
        new Product("iron_ingot", "Iron Ingot", Items.IRON_INGOT, 48, 24, 16),
        new Product("gold_ingot", "Gold Ingot", Items.GOLD_INGOT, 72, 36, 16),
        new Product("diamond", "Diamond", Items.DIAMOND, 240, 120, 8),
        new Product("ender_pearl", "Ender Pearl", Items.ENDER_PEARL, 90, 45, 8),
        new Product("golden_apple", "Golden Apple", Items.GOLDEN_APPLE, 320, 160, 4),
        new Product("experience_bottle", "Bottle o' Enchanting", Items.EXPERIENCE_BOTTLE, 110, 55, 8),
        new Product("totem_of_undying", "Totem of Undying", Items.TOTEM_OF_UNDYING, 1800, 900, 1)
    );

    private ShopCatalog() {}

    public static Product find(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return PRODUCTS.stream().filter(product -> product.id().equals(normalized)).findFirst().orElse(null);
    }
}
