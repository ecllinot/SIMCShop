package com.example.smcshop;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class ShopPriceTooltip {
    private static final long CACHE_TTL_MS = 60_000;
    private static final long RETRY_DELAY_MS = 10_000;
    private static final int CACHE_LIMIT = 128;
    private static final Map<String, Prices> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private ShopPriceTooltip() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (stack.isEmpty() || !SmcShopClient.isTargetServer(client)) return;
            boolean shift = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
            if (!shift) {
                lines.add(Text.literal("按下 Shift 显示物品价格").formatted(Formatting.GRAY));
                return;
            }

            String item = stack.getName().getString().trim();
            Prices prices = CACHE.get(item);
            if (prices == null) {
                prices = new Prices();
                CACHE.put(item, prices);
                if (CACHE.size() > CACHE_LIMIT) CACHE.remove(CACHE.keySet().iterator().next());
            }
            if (!prices.loading && System.currentTimeMillis() >= prices.refreshAt) {
                load(client, item, prices);
            }
            if (prices.sell == null) {
                lines.add(Text.literal(prices.loading ? "正在查询物品价格…" : "价格查询失败，请稍后再试")
                    .formatted(Formatting.GRAY));
                return;
            }
            appendPrices(lines, "最低出售价格", prices.sell, Formatting.GREEN);
            appendPrices(lines, "最高收购价格", prices.buy, Formatting.GOLD);
            if (prices.failed) {
                lines.add(Text.literal("更新失败，显示上次查询结果").formatted(Formatting.GRAY));
            }
        });
    }

    private static void load(MinecraftClient client, String item, Prices prices) {
        prices.loading = true;
        CompletableFuture<List<ShopResult>> sell = SmcShopClient.queryShops(item, "SELL", 3, true);
        CompletableFuture<List<ShopResult>> buy = SmcShopClient.queryShops(item, "BUY", 3, true);
        sell.thenCombine(buy, (sellShops, buyShops) -> new Listings(
            bestPrices(sellShops, item, true), bestPrices(buyShops, item, false)
        )).whenComplete((listings, error) -> client.execute(() -> {
            prices.loading = false;
            prices.failed = error != null;
            prices.refreshAt = System.currentTimeMillis() + (error == null ? CACHE_TTL_MS : RETRY_DELAY_MS);
            if (error == null) {
                prices.sell = listings.sell();
                prices.buy = listings.buy();
            }
        }));
    }

    private static List<ShopResult> bestPrices(List<ShopResult> shops, String item, boolean sell) {
        return shops.stream()
            .filter(shop -> item.equals(shop.item()) && (sell ? shop.isSell() : "BUY".equalsIgnoreCase(shop.type())))
            .sorted(Comparator.comparingInt((ShopResult shop) -> shop.amount() == 0 ? 1 : 0)
                .thenComparingDouble(shop -> sell ? shop.price() : -shop.price()))
            .limit(3)
            .toList();
    }

    private static void appendPrices(List<Text> lines, String heading, List<ShopResult> shops, Formatting color) {
        lines.add(Text.literal(heading).formatted(color, Formatting.BOLD));
        if (shops.isEmpty()) {
            lines.add(Text.literal("  暂无商店").formatted(Formatting.GRAY));
            return;
        }
        for (ShopResult shop : shops) {
            String port = shop.nearestPort().isBlank() ? "未知港口" : shop.nearestPort();
            String stock = shop.amount() == 0 ? " [缺货]" : "";
            lines.add(Text.literal("  $" + SmcShopClient.trimPrice(shop.price()) + " · " + port + stock)
                .formatted(color));
            lines.add(Text.literal("    坐标 " + shop.x() + ", " + shop.y() + ", " + shop.z())
                .formatted(Formatting.GRAY));
        }
    }

    private static final class Prices {
        private List<ShopResult> sell;
        private List<ShopResult> buy;
        private boolean loading;
        private boolean failed;
        private long refreshAt;
    }

    private record Listings(List<ShopResult> sell, List<ShopResult> buy) {}
}
