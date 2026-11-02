package com.example.smalltools.client;

import com.example.smalltools.menu.WashStoneMenu;
import com.example.smalltools.net.WashActionPayload;
import com.example.smalltools.net.WashStoneSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔洗炼界面（魔法风格）。
 *
 * <h2>布局</h2>
 *
 * <pre>
 *   y=8    标题
 *   y=20   运气文字    y=30 运气条
 *   y=58   输入槽（18×18，水平居中）
 *   y=86   分隔线       y=84~94 提示
 *   y=104  选项按钮（每条 20 高，步进 23）
 *   y=186  物品栏那一行（9 格，每格带分界线）
 * </pre>
 *
 * <h2>槽位对齐</h2>
 *
 * <p>菜单里注册槽位时坐标填的是占位值，真正的位置在 {@link #init()} 里按面板
 * 偏移统一设置 —— 这样"画在哪"和"点在哪"永远一致。
 * 之前菜单填 (0,0)、界面按居中画，两边对不上就错位了。</p>
 */
public class WashStoneScreen extends AbstractContainerScreen<WashStoneMenu> {

    private static final int PANEL_W = WashStoneMenu.PANEL_W;
    private static final int PANEL_H = WashStoneMenu.PANEL_H;
    private static final int SLOT_U = WashStoneMenu.SLOT_U;
    private static final int SLOT_V = WashStoneMenu.SLOT_V;

    private static final int ROW_W = 160;
    private static final int ROW_H = 20;
    private static final int ROW_STEP = 23;
    private static final int LIST_V = 104;

    private static final int INV_U = WashStoneMenu.INV_U;
    private static final int INV_V = WashStoneMenu.INV_V;

    private static final int PANEL_TOP = 0xD8140824;
    private static final int PANEL_BOTTOM = 0xE0080416;
    private static final int ACCENT = 0xFFB47CFF;
    private static final int ACCENT_SOFT = 0xFF8B5CF6;
    private static final int GOLD = 0xFFFFD96A;
    private static final int TITLE = 0xFFE8D8FF;
    private static final int MUTED = 0xFF9A8AB0;
    private static final int DANGER = 0xFFFF6B6B;
    private static final int OK = 0xFF7CF0C0;
    private static final int STAR = 0xFFD8C4FF;

    private List<WashStoneSyncPayload.Entry> entries = List.of();
    private List<WashStoneSyncPayload.Entry> candidates = List.of();
    private int selected = -1;
    private boolean locked;
    private int luckPercent;

    private final List<Button> rowButtons = new ArrayList<>();

    public WashStoneScreen(WashStoneMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelY = 9999;
    }

    @Override
    protected void init() {
        super.init();
        rebuildRows();
        PacketDistributor.sendToServer(
                new WashActionPayload(this.menu.containerId, WashActionPayload.KIND_REFRESH, 0));
    }

    public void accept(WashStoneSyncPayload payload) {
        if (payload.containerId() != this.menu.containerId) {
            return;
        }
        this.entries = payload.entries();
        this.candidates = payload.candidates();
        this.selected = payload.selected();
        this.locked = payload.locked();
        this.luckPercent = payload.luckPercent();
        rebuildRows();
    }

    private void rebuildRows() {
        for (Button button : this.rowButtons) {
            this.removeWidget(button);
        }
        this.rowButtons.clear();

        boolean showingCandidates = !this.candidates.isEmpty();
        List<WashStoneSyncPayload.Entry> rows = showingCandidates ? this.candidates : this.entries;

        int x = this.leftPos + (PANEL_W - ROW_W) / 2;
        int y = this.topPos + LIST_V;

        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            Component label = rows.get(i).name();
            boolean isSelected = !showingCandidates && i == this.selected;
            if (isSelected) {
                label = Component.literal("✦ ").append(label).withStyle(ChatFormatting.YELLOW);
            }
            Button button = MagicButton.create(
                    Button.builder(label, b -> onRowClicked(showingCandidates, index))
                            .bounds(x, y + i * ROW_STEP, ROW_W, ROW_H),
                    isSelected);
            this.addRenderableWidget(button);
            this.rowButtons.add(button);
        }
    }

    private void onRowClicked(boolean showingCandidates, int index) {
        int kind = showingCandidates ? WashActionPayload.KIND_CANDIDATE : WashActionPayload.KIND_ENTRY;
        PacketDistributor.sendToServer(new WashActionPayload(this.menu.containerId, kind, index));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float t = now / 1000.0F;

        int left = this.leftPos;
        int top = this.topPos;
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;

        graphics.fillGradient(left, top, right, bottom, PANEL_TOP, PANEL_BOTTOM);

        float breathe = 0.55F + 0.45F * Mth.sin(t * 1.6F);
        border(graphics, left, top, right, bottom,
                withAlpha(ACCENT_SOFT, (int) (140 + 100 * breathe)));

        corner(graphics, left, top, 1, 1, ACCENT);
        corner(graphics, right - 1, top, -1, 1, ACCENT);
        corner(graphics, left, bottom - 1, 1, -1, ACCENT);
        corner(graphics, right - 1, bottom - 1, -1, -1, ACCENT);

        drawStars(graphics, left + PANEL_W / 2.0F, top + SLOT_V + 9.0F, t);
        drawInputSlot(graphics, now);
        drawLuckBar(graphics, now);
        drawInventoryRow(graphics);
    }

    private static void border(GuiGraphics g, int l, int t, int r, int b, int color) {
        g.fill(l, t, r, t + 1, color);
        g.fill(l, b - 1, r, b, color);
        g.fill(l, t, l + 1, b, color);
        g.fill(r - 1, t, r, b, color);
    }

    private static void corner(GuiGraphics g, int x, int y, int dx, int dy, int color) {
        g.fill(x, y, x + dx * 6, y + dy, color);
        g.fill(x, y, x + dx, y + dy * 6, color);
    }

    private void drawStars(GuiGraphics g, float cx, float cy, float t) {
        for (int i = 0; i < 8; i++) {
            double angle = t * 0.5 + i * (Math.PI * 2 / 8);
            float radius = 34.0F + Mth.sin(t * 1.1F + i) * 4.0F;
            int sx = (int) (cx + Math.cos(angle) * radius * 1.5F);
            int sy = (int) (cy + Math.sin(angle) * radius * 0.55F);
            float twinkle = 0.35F + 0.65F * Mth.sin(t * 2.3F + i * 1.7F);
            int alpha = (int) Mth.clamp(50 + 140 * twinkle, 0, 255);
            g.fill(sx, sy, sx + 1, sy + 1, withAlpha(STAR, alpha));
        }
    }

    private void drawInputSlot(GuiGraphics g, long now) {
        int sx = this.leftPos + SLOT_U;
        int sy = this.topPos + SLOT_V;

        g.fill(sx - 2, sy - 2, sx + 20, sy + 20, withAlpha(ACCENT_SOFT, 200));
        g.fill(sx - 1, sy - 1, sx + 19, sy + 19, 0xFF0B0417);
        g.fillGradient(sx - 1, sy - 1, sx + 19, sy + 8,
                withAlpha(ACCENT, 70), withAlpha(ACCENT, 0));

        float breathe = 0.5F + 0.5F * Mth.sin(now / 420.0F);
        int dot = withAlpha(GOLD, (int) (120 + 120 * breathe));
        g.fill(sx - 2, sy - 2, sx, sy, dot);
        g.fill(sx + 18, sy - 2, sx + 20, sy, dot);
        g.fill(sx - 2, sy + 18, sx, sy + 20, dot);
        g.fill(sx + 18, sy + 18, sx + 20, sy + 20, dot);
    }

    /**
     * 物品栏那一行：9 个独立格子，每格都有边框，形成清晰的分界线。
     *
     * <p>不加分界线时 9 格连成一片深色，看不出物品属于哪一格。</p>
     */
    private void drawInventoryRow(GuiGraphics g) {
        int u = this.leftPos + INV_U;
        int v = this.topPos + INV_V;

        for (int i = 0; i < 9; i++) {
            int x = u + i * 18;
            g.fill(x - 1, v - 1, x + 17, v + 17, 0xFF241436);
            g.fill(x, v, x + 16, v + 16, 0xFF140B22);
            g.fill(x - 1, v - 1, x + 17, v, withAlpha(ACCENT_SOFT, 110));
            g.fill(x - 1, v + 16, x + 17, v + 17, withAlpha(ACCENT_SOFT, 70));
            g.fill(x - 1, v - 1, x, v + 17, withAlpha(ACCENT_SOFT, 110));
            g.fill(x + 16, v - 1, x + 17, v + 17, withAlpha(ACCENT_SOFT, 70));
        }
    }

    private void drawLuckBar(GuiGraphics g, long now) {
        int u = this.leftPos + 20;
        int v = this.topPos + 30;
        int w = PANEL_W - 40;
        int h = 4;

        g.fill(u, v, u + w, v + h, 0xFF1A0F2A);
        int filled = (int) (w * Mth.clamp(luckPercent / 100.0F, 0.0F, 1.0F));

        boolean full = luckPercent >= 100;
        int colTop;
        int colBottom;
        if (full) {
            float pulse = 0.6F + 0.4F * Mth.sin(now / 180.0F);
            colTop = withAlpha(GOLD, (int) (200 + 55 * pulse));
            colBottom = withAlpha(0xFFFFAA22, (int) (180 + 70 * pulse));
        } else {
            colTop = ACCENT;
            colBottom = ACCENT_SOFT;
        }
        if (filled > 0) {
            g.fillGradient(u, v, u + filled, v + h, colTop, colBottom);
        }
        g.fill(u - 1, v - 1, u + w + 1, v, withAlpha(ACCENT_SOFT, 120));
        g.fill(u - 1, v + h, u + w + 1, v + h + 1, withAlpha(ACCENT_SOFT, 90));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + PANEL_W / 2;
        boolean full = this.luckPercent >= 100;

        graphics.drawString(this.font,
                Component.translatable("screen.smalltools.wash.luck", this.luckPercent),
                this.leftPos + 20, this.topPos + 20, full ? GOLD : MUTED, false);

        graphics.fill(this.leftPos + 16, this.topPos + 86,
                this.leftPos + PANEL_W - 16, this.topPos + 87,
                withAlpha(ACCENT_SOFT, 90));

        Component hint;
        int color = MUTED;

        if (!this.candidates.isEmpty()) {
            hint = Component.translatable("screen.smalltools.wash.pick_candidate");
            color = ACCENT;
        } else if (this.locked) {
            hint = Component.translatable("screen.smalltools.wash.no_candidate");
            color = DANGER;
        } else if (!this.entries.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.smalltools.wash.warning")
                            .withStyle(ChatFormatting.RED),
                    centerX, this.topPos + 84, DANGER);
            hint = Component.translatable("screen.smalltools.wash.pick_entry");
        } else {
            hint = Component.translatable("screen.smalltools.wash.insert");
        }
        graphics.drawCenteredString(this.font, hint, centerX, this.topPos + 94, color);

        if (this.locked) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.smalltools.wash.consumed")
                            .withStyle(ChatFormatting.YELLOW),
                    centerX, this.topPos + 84, OK);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.imageWidth / 2;
        int half = this.font.width(this.title) / 2;
        graphics.drawString(this.font, "✦", centerX - half - 10, 9, ACCENT, false);
        graphics.drawString(this.font, this.title, centerX - half, 8, TITLE, false);
        graphics.drawString(this.font, "✦", centerX + half + 5, 9, ACCENT, false);
    }

    private static int withAlpha(int argb, int alpha) {
        int a = Mth.clamp(alpha, 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
