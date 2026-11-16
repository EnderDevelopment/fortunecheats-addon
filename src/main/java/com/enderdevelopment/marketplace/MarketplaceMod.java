package com.enderdevelopment.marketplace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.concurrent.ThreadLocalRandom;

public final class MarketplaceMod implements ModInitializer {
    private static final String OBJECTIVE_ID = "market_cash";
    private static final String CURRENCY = "$";
    private static final int GIFT_CHECK_INTERVAL_TICKS = 20 * 60 * 5;
    private static final int GIFT_CHANCE_PERCENT = 20;
    private static int ticksUntilGiftCheck = GIFT_CHECK_INTERVAL_TICKS;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("money")
                .executes(context -> showBalance(context.getSource().getPlayerOrException()))
                .then(Commands.literal("pay")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000000))
                            .executes(context -> pay(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "player"),
                                IntegerArgumentType.getInteger(context, "amount")
                            ))))));

            dispatcher.register(Commands.literal("shop")
                .executes(context -> listShop(context.getSource().getPlayerOrException()))
                .then(Commands.literal("buy")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                            .executes(context -> buy(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "item"),
                                IntegerArgumentType.getInteger(context, "amount")
                            )))))
                .then(Commands.literal("sell")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                            .executes(context -> sell(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "item"),
                                IntegerArgumentType.getInteger(context, "amount")
                            ))))));

            dispatcher.register(Commands.literal("marketgift")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(context -> giftPlayer(
                        context.getSource().getServer(),
                        StringArgumentType.getString(context, "player")
                    ))));
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tickGiftTimer);
    }

    private static Objective objective(MinecraftServer server) {
        var scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_ID);
        if (objective == null) {
            objective = scoreboard.addObjective(
                OBJECTIVE_ID,
                ObjectiveCriteria.DUMMY,
                Component.literal("Market Cash"),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null
            );
        }
        return objective;
    }

    private static ScoreAccess balance(MinecraftServer server, ServerPlayer player) {
        return server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.fromGameProfile(player.getGameProfile()), objective(server));
    }

    private static int showBalance(ServerPlayer player) {
        int amount = balance(player.getServer(), player).get();
        player.sendSystemMessage(Component.literal("Balance: " + CURRENCY + amount));
        return 1;
    }

    private static int listShop(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Shop catalog (prices per item):"));
        for (ShopCatalog.Product product : ShopCatalog.PRODUCTS) {
            player.sendSystemMessage(Component.literal("- " + product.id() + " | " + product.label()
                + " | buy " + CURRENCY + product.buyPrice() + " | sell " + CURRENCY + product.sellPrice()));
        }
        player.sendSystemMessage(Component.literal("Use /shop buy <item> <amount> or /shop sell <item> <amount>."));
        return ShopCatalog.PRODUCTS.size();
    }

    private static int buy(ServerPlayer player, String itemId, int amount) {
        ShopCatalog.Product product = ShopCatalog.find(itemId);
        if (product == null) {
            player.sendSystemMessage(Component.literal("Unknown item. Use /shop to view the catalog."));
            return 0;
        }
        int cost = product.buyPrice() * amount;
        ScoreAccess balance = balance(player.getServer(), player);
        if (balance.get() < cost) {
            player.sendSystemMessage(Component.literal("Not enough money. Cost: " + CURRENCY + cost + "."));
            return 0;
        }
        if (!canFit(player.getInventory(), product.item(), amount)) {
            player.sendSystemMessage(Component.literal("Not enough inventory space."));
            return 0;
        }
        balance.add(-cost);
        giveItems(player, product.item(), amount);
        player.sendSystemMessage(Component.literal("Bought " + amount + " " + product.label() + " for " + CURRENCY + cost + "."));
        return 1;
    }

    private static int sell(ServerPlayer player, String itemId, int amount) {
        ShopCatalog.Product product = ShopCatalog.find(itemId);
        if (product == null) {
            player.sendSystemMessage(Component.literal("Unknown item. Use /shop to view the catalog."));
            return 0;
        }
        int owned = countItems(player.getInventory(), product.item());
        if (owned < amount) {
            player.sendSystemMessage(Component.literal("You only have " + owned + " " + product.label() + "."));
            return 0;
        }
        removeItems(player.getInventory(), product.item(), amount);
        int proceeds = product.sellPrice() * amount;
        balance(player.getServer(), player).add(proceeds);
        player.sendSystemMessage(Component.literal("Sold " + amount + " " + product.label() + " for " + CURRENCY + proceeds + "."));
        return 1;
    }

    private static int pay(ServerPlayer sender, String targetName, int amount) {
        ServerPlayer target = sender.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            sender.sendSystemMessage(Component.literal("That player is not online."));
            return 0;
        }
        if (target.getUUID().equals(sender.getUUID())) {
            sender.sendSystemMessage(Component.literal("You cannot pay yourself."));
            return 0;
        }
        ScoreAccess senderBalance = balance(sender.getServer(), sender);
        if (senderBalance.get() < amount) {
            sender.sendSystemMessage(Component.literal("Not enough money."));
            return 0;
        }
        senderBalance.add(-amount);
        balance(sender.getServer(), target).add(amount);
        sender.sendSystemMessage(Component.literal("Paid " + target.getName().getString() + " " + CURRENCY + amount + "."));
        target.sendSystemMessage(Component.literal("Received " + CURRENCY + amount + " from " + sender.getName().getString() + "."));
        return 1;
    }

    private static boolean canFit(Inventory inventory, Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                remaining -= item.getDefaultMaxStackSize();
            } else if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                remaining -= stack.getMaxStackSize() - stack.getCount();
            }
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    private static void giveItems(ServerPlayer player, Item item, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, stackSize);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= stackSize;
        }
    }

    private static int countItems(Inventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void removeItems(Inventory inventory, Item item, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    private void tickGiftTimer(MinecraftServer server) {
        if (--ticksUntilGiftCheck > 0) return;
        ticksUntilGiftCheck = GIFT_CHECK_INTERVAL_TICKS;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ThreadLocalRandom.current().nextInt(100) < GIFT_CHANCE_PERCENT) {
                grantGift(player);
            }
        }
    }

    private static int giftPlayer(MinecraftServer server, String targetName) {
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            return 0;
        }
        grantGift(target);
        return 1;
    }

    private static void grantGift(ServerPlayer player) {
        ShopCatalog.Product product = ShopCatalog.PRODUCTS.get(ThreadLocalRandom.current().nextInt(ShopCatalog.PRODUCTS.size() - 1));
        int amount = ThreadLocalRandom.current().nextInt(1, product.stackSize() + 1);
        giveItems(player, product.item(), amount);
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < 24; i++) {
            double angle = (Math.PI * 2.0 * i) / 24.0;
            double x = player.getX() + Math.cos(angle) * 0.8;
            double z = player.getZ() + Math.sin(angle) * 0.8;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, player.getY() + 1.0 + (i % 4) * 0.2, z, 1, 0, 0.08, 0, 0.01);
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.35, 0.5, 0.35, 0.05);
        player.sendSystemMessage(Component.literal("Lucky drop! You received " + amount + " " + product.label() + " for free."));
    }
}
