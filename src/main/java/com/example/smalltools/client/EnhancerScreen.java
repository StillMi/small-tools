package com.example.smalltools.client;

import com.example.smalltools.menu.EnhancerMenu;
import com.example.smalltools.net.EnhanceActionPayload;
import com.example.smalltools.net.EnhancerSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 附魔强化界面。
 *
 * <p>布局沿用洗炼界面的魔法紫风格，但配色偏"青金"（深蓝 + 金），
 * 和凝造青金呼应，避免两个界面看起来一模一样。</p>
 */
public class EnhancerScreen extends AbstractContainerScreen<EnhancerMenu> {

    private static final int PANEL_W = EnhancerMenu.PANEL_W;
    private static final int PANEL_H = EnhancerMenu.PANEL_H;
    private static final int SLOT_U = EnhancerMenu.SLOT_U;
    private static final int SLOT_V = EnhancerMenu.SLOT_V;
    private static final int INV_U = EnhancerMenu.INV_U;
    private static final int INV_V = EnhancerMenu.INV_V;

    private static final int LIST_V = 108;
    private static final int ROW_STEP = 13;

    private static final int PANEL_TOP = 0xD8081428;
    private static final int PANEL_BOTTOM = 0xE0040814;
    private static final int ACCENT = 0xFF7CC4FF;
    private static final int ACCENT_SOFT = 0xFF3B82F6;
    private static final int GOLD = 0xFFFFD96A;
    private static final int TITLE = 0xFFD8EAFF;
    private static final int MUTED = 0xFF8AA0B8;
    private static final int OK = 0xFF7CF0C0;
    private static final int DANGER = 0xFFFF6B6B;
    private static final int STAR = 0xFFC4DCFF;

    private List<EnhancerSyncPayload.Entry> enchantments = List.of();
    private int cost;
    private int lapis;
    private int used;
    private Component result = Component.empty();

    private Button enhanceButton;

    public EnhancerScreen(EnhancerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelY = 9999;
    }

    @Override
    protected void init() {
        super.init();

        enhanceButton = MagicButton.create(
                Button.builder(Component.translatable("screen.smalltools.enhancer.button"),
                                b -> PacketDistributor.sendToServer(new EnhanceActionPayload(
                                        this.menu.containerId, EnhanceActionPayload.KIND_ENHANCE)))
                        .bounds(this.leftPos + (PANEL_W - 120) / 2,
                                this.topPos + EnhancerMenu.BUTTON_V, 120, 20),
                false);
        this.addRenderableWidget(enhanceButton);

        PacketDistributor.sendToServer(new EnhanceActionPayload(
                this.menu.containerId, EnhanceActionPayload.KIND_REFRESH));
    }

    public void accept(EnhancerSyncPayload payload) {
        if (payload.containerId() != this.menu.containerId) {
            return;
        }
        this.enchantments = payload.enchantments();
        this.cost = payload.cost();
        this.lapis = payload.lapis();
        this.used = payload.used();
        this.result = payload.result();
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

        float breathe = 0.55F + 0.45F * Mth.sin(t * 1.4F);
        int edge = withAlpha(ACCENT_SOFT, (int) (140 + 100 * breathe));
        graphics.fill(left, top, right, top + 1, edge);
        graphics.fill(left, bottom - 1, right, bottom, edge);
        graphics.fill(left, top, left + 1, bottom, edge);
        graphics.fill(right - 1, top, right, bottom, edge);

        // 星尘（冷色调，比洗炼界面收敛）
        for (int i = 0; i < 7; i++) {
            double angle = t * 0.45 + i * (Math.PI * 2 / 7);
            float radius = 30.0F;
            int sx = (int) (left + PANEL_W / 2.0F + Math.cos(angle) * radius * 1.5F);
            int sy = (int) (top + SLOT_V + 9 + Math.sin(angle) * radius * 0.5F);
            float twinkle = 0.35F + 0.65F * Mth.sin(t * 2.1F + i);
            graphics.fill(sx, sy, sx + 1, sy + 1,
                    withAlpha(STAR, (int) Mth.clamp(40 + 130 * twinkle, 0, 255)));
        }

        drawInputSlot(graphics, now);
        drawInventoryRow(graphics);
    }

    private void drawInputSlot(GuiGraphics g, long now) {
        int sx = this.leftPos + SLOT_U;
        int sy = this.topPos + SLOT_V;
        g.fill(sx - 2, sy - 2, sx + 20, sy + 20, withAlpha(ACCENT_SOFT, 200));
        g.fill(sx - 1, sy - 1, sx + 19, sy + 19, 0xFF060C18);
        float breathe = 0.5F + 0.5F * Mth.sin(now / 420.0F);
        int dot = withAlpha(GOLD, (int) (120 + 120 * breathe));
        g.fill(sx - 2, sy - 2, sx, sy, dot);
        g.fill(sx + 18, sy - 2, sx + 20, sy, dot);
        g.fill(sx - 2, sy + 18, sx, sy + 20, dot);
        g.fill(sx + 18, sy + 18, sx + 20, sy + 20, dot);
    }

    private void drawInventoryRow(GuiGraphics g) {
        int u = this.leftPos + INV_U;
        int v = this.topPos + INV_V;
        for (int i = 0; i < 9; i++) {
            int x = u + i * 18;
            g.fill(x - 1, v - 1, x + 17, v + 17, 0xFF16223A);
            g.fill(x, v, x + 16, v + 16, 0xFF0A1120);
            g.fill(x - 1, v - 1, x + 17, v, withAlpha(ACCENT_SOFT, 110));
            g.fill(x - 1, v + 16, x + 17, v + 17, withAlpha(ACCENT_SOFT, 70));
            g.fill(x - 1, v - 1, x, v + 17, withAlpha(ACCENT_SOFT, 110));
            g.fill(x + 16, v - 1, x + 17, v + 17, withAlpha(ACCENT_SOFT, 70));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + PANEL_W / 2;
        boolean exhausted = this.used >= EnhancerMenu.MAX_ENHANCES;

        // ---- 附魔列表 ----
        int y = this.topPos + LIST_V;
        if (this.enchantments.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.smalltools.enhancer.insert"),
                    centerX, this.topPos + 92, MUTED);
        } else {
            for (EnhancerSyncPayload.Entry e : this.enchantments) {
                int color = e.maxed() ? GOLD : TITLE;
                graphics.drawString(this.font, e.name(), this.leftPos + 26, y, color, false);
                if (e.maxed()) {
                    graphics.drawString(this.font, "MAX", this.leftPos + PANEL_W - 46, y,
                            GOLD, false);
                }
                y += ROW_STEP;
            }
        }

        // ---- 强化次数进度条 ----
        int barU = this.leftPos + 20;
        int barV = this.topPos + 160;
        int barW = PANEL_W - 40;
        int barH = 6;

        graphics.drawString(this.font,
                Component.translatable("screen.smalltools.enhancer.progress",
                        this.used, EnhancerMenu.MAX_ENHANCES),
                barU, barV - 11, exhausted ? DANGER : MUTED, false);

        graphics.fill(barU - 1, barV - 1, barU + barW + 1, barV + barH + 1,
                withAlpha(ACCENT_SOFT, 120));
        graphics.fill(barU, barV, barU + barW, barV + barH, 0xFF0A1120);

        // 五段式格子：一眼看出还剩几次
        int segGap = 2;
        int segW = (barW - segGap * (EnhancerMenu.MAX_ENHANCES - 1)) / EnhancerMenu.MAX_ENHANCES;
        for (int i = 0; i < EnhancerMenu.MAX_ENHANCES; i++) {
            int segX = barU + i * (segW + segGap);
            boolean filled = i < this.used;
            int col = filled ? (exhausted ? DANGER : GOLD) : 0xFF16223A;
            graphics.fill(segX, barV, segX + segW, barV + barH, col);
        }

        // ---- 消耗与持有量 ----
        int costColor = this.lapis >= this.cost && !exhausted ? OK : DANGER;
        Component costText = exhausted
                ? Component.translatable("screen.smalltools.enhancer.exhausted")
                : Component.translatable("screen.smalltools.enhancer.cost", this.cost);
        graphics.drawCenteredString(this.font, costText, centerX,
                this.topPos + 170, costColor);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.smalltools.enhancer.have", this.lapis),
                centerX, this.topPos + 180, MUTED);

        // ---- 结果提示（放在列表下方，不和槽/按钮抢位置）----
        if (!this.result.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.result, centerX,
                    this.topPos + 96, OK);
        }

        if (this.enhanceButton != null) {
            this.enhanceButton.active = !exhausted && this.lapis >= this.cost
                    && this.cost > 0;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int centerX = this.imageWidth / 2;
        int half = this.font.width(this.title) / 2;
        graphics.drawString(this.font, "◆", centerX - half - 10, 9, ACCENT, false);
        graphics.drawString(this.font, this.title, centerX - half, 8, TITLE, false);
        graphics.drawString(this.font, "◆", centerX + half + 5, 9, ACCENT, false);
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
