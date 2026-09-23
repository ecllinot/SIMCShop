package com.example.smcshop;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

final class SmcShopSearchScreen extends Screen {
    private static final int HEADER_HEIGHT = 78;
    private static final int ROW_HEIGHT = 52;
    private static final int SIDE_PADDING = 18;

    private TextFieldWidget searchBox;
    private ButtonWidget allButton;
    private ButtonWidget sellButton;
    private ButtonWidget buyButton;
    private ButtonWidget searchButton;
    private String type = "";
    private String status = "输入物品名称后搜索";
    private List<ShopResult> results = new ArrayList<>();
    private int scroll;
    private boolean loading;

    SmcShopSearchScreen() {
        super(Text.literal("SIMCShop"));
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(560, this.width - SIDE_PADDING * 2);
        int left = (this.width - contentWidth) / 2;
        int top = 26;
        int buttonY = top + 28;

        this.searchBox = new TextFieldWidget(this.textRenderer, left, top, contentWidth - 72, 20, Text.literal("物品名称"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setPlaceholder(Text.literal("搜索物品名称"));
        this.addDrawableChild(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.searchButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("搜索"), button -> runSearch())
            .dimensions(left + contentWidth - 64, top, 64, 20)
            .build());
        this.allButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("全部"), button -> setType(""))
            .dimensions(left, buttonY, 58, 20)
            .build());
        this.sellButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("出售"), button -> setType("SELL"))
            .dimensions(left + 64, buttonY, 58, 20)
            .build());
        this.buyButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("收购"), button -> setType("BUY"))
            .dimensions(left + 128, buttonY, 58, 20)
            .build());
        updateFilterButtons();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == 257 || input.key() == 335) {
            runSearch();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        BlockPos shop = coordinateAt(click.x(), click.y());
        if (shop != null) {
            SmcShopClient.highlightShop(shop);
            this.status = "已标记坐标：" + shop.getX() + ", " + shop.getY() + ", " + shop.getZ();
            return true;
        }
        String port = portAt(click.x(), click.y());
        if (port != null) {
            MinecraftClient.getInstance().keyboard.setClipboard(port);
            this.status = "已复制港口：" + port;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private BlockPos coordinateAt(double mouseX, double mouseY) {
        int contentWidth = Math.min(560, this.width - SIDE_PADDING * 2);
        int left = (this.width - contentWidth) / 2;
        int listTop = HEADER_HEIGHT;
        int listBottom = this.height - 18;
        if (mouseX < left || mouseX > left + contentWidth || mouseY < listTop || mouseY > listBottom) return null;
        int index = scroll + ((int) mouseY - listTop) / ROW_HEIGHT;
        if (index < 0 || index >= results.size()) return null;
        ShopResult shop = results.get(index);
        int rowY = listTop + (index - scroll) * ROW_HEIGHT;
        String ownerPrefix = shop.owner() + " | ";
        String coords = shop.x() + ", " + shop.y() + ", " + shop.z();
        int coordsLeft = left + contentWidth / 2 + this.textRenderer.getWidth(ownerPrefix);
        int coordsRight = coordsLeft + this.textRenderer.getWidth(coords);
        return mouseX >= coordsLeft && mouseX <= coordsRight && mouseY >= rowY + 31 && mouseY <= rowY + 48
            ? new BlockPos(shop.x(), shop.y(), shop.z()) : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, results.size() - visibleRows());
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(verticalAmount)));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int contentWidth = Math.min(560, this.width - SIDE_PADDING * 2);
        int left = (this.width - contentWidth) / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("SIMCShop"), this.width / 2, 10, 0xFFF4A340);
        context.drawTextWithShadow(this.textRenderer, status, left + 198, 58, 0xFFD8D8D8);

        int listTop = HEADER_HEIGHT;
        int listBottom = this.height - 18;
        context.enableScissor(left, listTop, left + contentWidth, listBottom);
        if (results.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, loading ? "查询中..." : "暂无结果", this.width / 2, listTop + 28, 0xFFBDBDBD);
        } else {
            int end = Math.min(results.size(), scroll + visibleRows() + 1);
            for (int i = scroll; i < end; i++) {
                renderRow(context, results.get(i), left, listTop + (i - scroll) * ROW_HEIGHT, contentWidth);
            }
        }
        context.disableScissor();
    }

    private void renderRow(DrawContext context, ShopResult shop, int x, int y, int width) {
        int background = shop.isSell() ? 0xAA2B2119 : 0xAA192429;
        int accent = shop.isSell() ? 0xFFF4A340 : 0xFF62C7D8;
        context.fill(x, y, x + width, y + ROW_HEIGHT - 3, background);
        context.fill(x, y, x + 3, y + ROW_HEIGHT - 3, accent);

        String price = "$" + SmcShopClient.trimPrice(shop.price());
        String amount = shop.amount() == 0 ? "缺货" : "x" + shop.amount();
        String coords = shop.x() + ", " + shop.y() + ", " + shop.z();

        context.drawTextWithShadow(this.textRenderer, shop.item(), x + 10, y + 6, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer, shop.typeLabel() + " " + price + " " + amount, x + 10, y + 20, accent);
        String ownerPrefix = shop.owner() + " | ";
        context.drawTextWithShadow(this.textRenderer, ownerPrefix, x + width / 2, y + 34, 0xFFD8D8D8);
        context.drawTextWithShadow(this.textRenderer, coords, x + width / 2 + this.textRenderer.getWidth(ownerPrefix), y + 34, 0xFFFFD27A);
        if (!shop.nearestPort().isBlank()) {
            int portCenter = x + width - 34;
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("复制港口"), portCenter, y + 4, 0xFF62C7D8);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(shop.nearestPort()), portCenter, y + 18, 0xFFB7EAF0);
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("[" + shop.nearestPortDistance() + "m]"), portCenter, y + 32, 0xFF9FD6DC);
        }
    }

    private String portAt(double mouseX, double mouseY) {
        int contentWidth = Math.min(560, this.width - SIDE_PADDING * 2);
        int left = (this.width - contentWidth) / 2;
        int right = left + contentWidth;
        int listTop = HEADER_HEIGHT;
        int listBottom = this.height - 18;
        if (mouseX < left || mouseX > right || mouseY < listTop || mouseY > listBottom) return null;
        int index = scroll + ((int) mouseY - listTop) / ROW_HEIGHT;
        if (index < 0 || index >= results.size()) return null;
        ShopResult shop = results.get(index);
        if (shop.nearestPort().isBlank()) return null;
        int rowY = listTop + (index - scroll) * ROW_HEIGHT;
        int copyLeft = right - 72;
        int copyRight = right - 4;
        return mouseX >= copyLeft && mouseX <= copyRight && mouseY >= rowY + 2 && mouseY <= rowY + 47 ? shop.nearestPort() : null;
    }

    private void setType(String nextType) {
        this.type = nextType;
        this.scroll = 0;
        updateFilterButtons();
        if (!this.searchBox.getText().isBlank()) runSearch();
    }

    private void updateFilterButtons() {
        if (allButton == null) return;
        allButton.active = !type.isBlank();
        sellButton.active = !"SELL".equals(type);
        buyButton.active = !"BUY".equals(type);
    }

    private void runSearch() {
        String item = this.searchBox.getText().trim();
        if (item.isBlank() || loading) return;
        this.loading = true;
        this.status = "正在查询 " + item;
        this.results = List.of();
        this.scroll = 0;
        this.searchButton.active = false;
        SmcShopClient.queryShops(item, type, 120).whenComplete((shops, error) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                loading = false;
                searchButton.active = true;
                if (error != null) {
                    status = "查询失败，请稍后再试";
                    results = List.of();
                    return;
                }
                results = shops;
                status = item + " 找到 " + shops.size() + " 家商店，点击坐标高亮";
            });
        });
    }

    private int visibleRows() {
        return Math.max(1, (this.height - HEADER_HEIGHT - 18) / ROW_HEIGHT);
    }
}
