package com.example.smcshop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import com.mojang.brigadier.arguments.StringArgumentType;

public final class SmcShopClient implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String API = System.getenv().getOrDefault("SMCSHOP_API_URL", "https://shop.whalemc.com");
    private static final int SEARCH_LIMIT = 80;
    private static KeyBinding openSearchKey;
    private static final Map<String, String> fingerprints = new HashMap<>();
    private static final ArrayList<JsonObject> queue = new ArrayList<>();
    private static final Pattern PRICE = Pattern.compile("^单价\\s*[：:]\\s*\\$\\s*([0-9]+(?:[,.][0-9]+)*)$");
    private static final Pattern STOCK = Pattern.compile("^(出售|收购)\\s+(\\d+)$");
    private static int tick;
    private static boolean targetServer;

    @Override
    public void onInitializeClient() {
        openSearchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.smcshop.open_search",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.create(Identifier.of("smcshop", "general"))
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSearchKey.wasPressed()) {
                client.setScreen(new SmcShopSearchScreen());
            }
            boolean currentTarget = isTargetServer(client);
            if (!currentTarget && targetServer) {
                queue.clear();
                fingerprints.clear();
            }
            targetServer = currentTarget;
            if (!targetServer) return;
            if (++tick % 100 == 0) scan(client);
            if (tick % 400 == 0) upload();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(literal("smcshop")
                .then(literal("gui")
                    .executes(context -> openSearchGui(context.getSource().getClient())))
                .then(literal("search").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), ""))))
                .then(literal("sell").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), "SELL"))))
                .then(literal("buy").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), "BUY"))))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(literal("simcshop")
                .then(literal("gui")
                    .executes(context -> openSearchGui(context.getSource().getClient())))
                .then(literal("search").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), ""))))
                .then(literal("sell").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), "SELL"))))
                .then(literal("buy").then(argument("item", StringArgumentType.greedyString())
                    .executes(context -> search(context.getSource().getClient(), StringArgumentType.getString(context, "item"), "BUY"))))));
    }

    private static boolean isTargetServer(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info == null) return false;
        String name = info.name == null ? "" : info.name;
        String address = info.address == null ? "" : info.address;
        String identity = (name + " " + address).toLowerCase(java.util.Locale.ROOT);
        return identity.contains("simmc");
    }

    private static boolean eligible(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        return targetServer && client.world != null && client.player != null && info != null;
    }

    private static void scan(MinecraftClient client) {
        if (!eligible(client)) return;
        BlockPos origin = client.player.getBlockPos();
        for (int x = -8; x <= 8; x++) for (int y = -3; y <= 3; y++) for (int z = -8; z <= 8; z++) {
            BlockPos pos = origin.add(x, y, z);
            if (!client.world.getBlockState(pos).getBlock().getTranslationKey().contains("sign")) continue;
            String[] lines = new String[0];
            if (client.world.getBlockEntity(pos) instanceof net.minecraft.block.entity.SignBlockEntity sign) {
                Text[] text = sign.getFrontText().getMessages(false);
                lines = java.util.Arrays.stream(text).map(Text::getString).toArray(String[]::new);
            }
            parse(lines, client, pos);
        }
    }

    private static void parse(String[] lines, MinecraftClient client, BlockPos pos) {
        if (lines.length < 4) return;
        String owner = lines[0].trim();
        String tradeLine = lines[1].trim();
        String item = lines[2].trim();
        String priceLine = lines[3].trim();
        if (owner.isEmpty() || item.isEmpty()) return;

        Matcher stock = STOCK.matcher(tradeLine);
        boolean outOfStock = tradeLine.equals("缺货");
        if (!stock.matches() && !outOfStock) return;

        Matcher price = PRICE.matcher(priceLine);
        if (!price.find()) return;
        String type = outOfStock || stock.group(1).equals("出售") ? "SELL" : "BUY";
        int amount = outOfStock ? 0 : Integer.parseInt(stock.group(2));
        JsonObject shop = new JsonObject();
        shop.addProperty("world", client.world.getRegistryKey().getValue().toString());
        shop.addProperty("x", pos.getX()); shop.addProperty("y", pos.getY()); shop.addProperty("z", pos.getZ());
        shop.addProperty("owner", owner);
        shop.addProperty("type", type); shop.addProperty("item", item);
        shop.addProperty("amount", amount);
        shop.addProperty("price", Double.parseDouble(price.group(1).replace(",", "")));
        String fingerprint = GSON.toJson(shop);
        String id = shop.get("world").getAsString() + ":" + pos + ":" + type + ":" + item;
        if (!fingerprint.equals(fingerprints.put(id, fingerprint))) queue.add(shop);
    }

    private static synchronized void upload() {
        if (queue.isEmpty()) return;
        ArrayList<JsonObject> batch = new ArrayList<>(queue.subList(0, Math.min(50, queue.size())));
        queue.subList(0, batch.size()).clear();
        JsonObject body = new JsonObject();
        body.add("shops", GSON.toJsonTree(batch));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/api/shops/batch"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).exceptionally(error -> null);
    }

    private static int openSearchGui(MinecraftClient client) {
        client.execute(() -> client.setScreen(new SmcShopSearchScreen()));
        return 1;
    }

    private static int search(MinecraftClient client, String item, String type) {
        queryShops(item, type, SEARCH_LIMIT).whenComplete((shops, error) -> client.execute(() -> {
            if (client.player == null) return;
            if (error != null) {
                client.player.sendMessage(Text.literal("SIMCShop: 查询失败，请稍后再试"), false);
                return;
            }
            client.player.sendMessage(Text.literal("SIMCShop: " + item + " 找到 " + shops.size() + " 家商店"), false);
            if (shops.isEmpty()) return;
            int shown = Math.min(8, shops.size());
            for (int i = 0; i < shown; i++) {
                client.player.sendMessage(formatChatLine(shops.get(i)), false);
            }
            if (shops.size() > shown) {
                client.player.sendMessage(Text.literal("还有 " + (shops.size() - shown) + " 条结果，可用 /smcshop gui 查看更多"), false);
            }
        }));
        return 1;
    }

    static CompletableFuture<List<ShopResult>> queryShops(String item, String type, int limit) {
        StringBuilder url = new StringBuilder(API).append("/api/shops?limit=").append(limit);
        if (item != null && !item.isBlank()) {
            url.append("&item=").append(URLEncoder.encode(item.trim(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (type != null && !type.isBlank()) {
            url.append("&type=").append(URLEncoder.encode(type, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                return parseShopResults(response.body());
            });
    }

    private static List<ShopResult> parseShopResults(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray shops = root.has("shops") && root.get("shops").isJsonArray() ? root.getAsJsonArray("shops") : new JsonArray();
        ArrayList<ShopResult> results = new ArrayList<>();
        for (JsonElement element : shops) {
            if (!element.isJsonObject()) continue;
            JsonObject shop = element.getAsJsonObject();
            results.add(new ShopResult(
                getString(shop, "item"),
                getString(shop, "owner"),
                getString(shop, "type"),
                getInt(shop, "amount"),
                getDouble(shop, "price"),
                getString(shop, "world"),
                getInt(shop, "x"),
                getInt(shop, "y"),
                getInt(shop, "z"),
                getString(shop, "nearestPort"),
                getInt(shop, "nearestPortDistance"),
                getString(shop, "observedAt")
            ));
        }
        results.sort(Comparator
            .comparingInt((ShopResult shop) -> shop.amount() == 0 ? 1 : 0)
            .thenComparingInt(shop -> shop.isSell() ? 0 : 1)
            .thenComparingDouble(shop -> shop.isSell() ? shop.price() : -shop.price()));
        return results;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int getInt(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static double getDouble(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
    }

    static MutableText formatChatLine(ShopResult shop) {
        String amount = shop.amount() == 0 ? "缺货" : "x" + shop.amount();
        MutableText line = Text.literal(shop.typeLabel() + " $" + trimPrice(shop.price()) + " " + amount + " " + shop.item()
            + " | " + shop.owner() + " | " + shop.x() + ", " + shop.y() + ", " + shop.z());
        if (!shop.nearestPort().isBlank()) {
            line.append(Text.literal(" | 最近港口 "));
            line.append(Text.literal(shop.nearestPort())
                .styled(style -> style
                    .withColor(Formatting.AQUA)
                    .withUnderline(true)
                    .withClickEvent(new ClickEvent.CopyToClipboard(shop.nearestPort()))));
            line.append(Text.literal("[" + shop.nearestPortDistance() + "m]"));
        }
        return line;
    }

    static String trimPrice(double price) {
        if (price == Math.rint(price)) return Long.toString(Math.round(price));
        return String.format(java.util.Locale.ROOT, "%.2f", price);
    }
}
