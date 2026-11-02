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
 * <h2>视觉设计</h2>
 *
 * <p>按"附魔台 + 星界"的调子做，不额外引入贴图，全部用原生的
 * {@code fill / fillGradient / drawCenteredString} 画出来：</p>
 *
 * <ul>
 *   <li><b>面板</b>：深紫近黑的渐变底，四边一圈发光描边；</li>
 *   <li><b>呼吸光晕</b>：面板外框的紫色每帧按正弦变化，像在缓缓呼吸；</li>
 *   <li><b>星尘</b>：一圈绕中心缓慢旋转的光点，位置由时间驱动；</li>
 *   <li><b>输入槽</b>：四角带光点的方形凹槽，槽内有一层淡紫反光；</li>
 *   <li><b>选项按钮</b>：改过底色 —— 未选中是暗紫、悬停时泛光、选中项描金边。</li>
 * </ul>
 *
 * <p>所有动画只用 {@code System.currentTimeMillis()} 驱动，不依赖 tick，
 * 所以即使暂停（{@link #isPauseScreen()} 为 false）也能继续呼吸。</p>
 */
public class WashStoneScreen extends AbstractContainerScreen<WashStoneMenu> {

    /** 选项行高。 */
    private static final int ROW_HEIGHT = 22;
    /** 选项按钮宽度。 */
    private static final int ROW_WIDTH = 150;
    /** 选项区顶部相对面板的偏移。 */
    private static final int LIST_TOP = 104;

    // ---- 配色 ----
    private static final int PANEL_TOP = 0xD8140824;
    private static final int PANEL_BOTTOM = 0xE0080416;
    private static final int PANEL_EDGE = 0xFF6A2FA8;
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

    private final List<Button> rowButtons = new ArrayList<>();

    public WashStoneScreen(WashStoneMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 190;
        this.imageHeight = 244;
    }

    @Override
    protected void init() {
        super.init();
        rebuildRows();
        PacketDistributor.sendToServer(
                new WashActionPayload(this.menu.containerId, WashActionPayload.KIND_REFRESH, 0));
    }

    /** 收到同步包后刷新按钮。 */
    public void accept(WashStoneSyncPayload payload) {
        if (payload.containerId() != this.menu.containerId) {
            return;
        }
        this.entries = payload.entries();
        this.candidates = payload.candidates();
        this.selected = payload.selected();
        this.locked = payload.locked();
        rebuildRows();
    }

    private void rebuildRows() {
        for (Button button : this.rowButtons) {
            this.removeWidget(button);
        }
        this.rowButtons.clear();

        boolean showingCandidates = !this.candidates.isEmpty();
        List<WashStoneSyncPayload.Entry> rows = showingCandidates ? this.candidates : this.entries;

        int x = this.leftPos + (this.imageWidth - ROW_WIDTH) / 2;
        int y = this.topPos + LIST_TOP;

        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            Component label = rows.get(i).name();
            if (!showingCandidates && i == this.selected) {
                label = Component.literal("✦ ").append(label).withStyle(ChatFormatting.YELLOW);
            }
            boolean isSelected = !showingCandidates && i == this.selected;
            Button button = MagicButton.create(
                    Button.builder(label, b -> onRowClicked(showingCandidates, index))
                            .bounds(x, y + i * ROW_HEIGHT, ROW_WIDTH, 20),
                    isSelected);
            this.addRenderableWidget(button);
            this.rowButtons.add(button);
        }
    }

    private void onRowClicked(boolean showingCandidates, int index) {
        int kind = showingCandidates ? WashActionPayload.KIND_CANDIDATE : WashActionPayload.KIND_ENTRY;
        PacketDistributor.sendToServer(new WashActionPayload(this.menu.containerId, kind, index));
    }

    // ------------------------------------------------------------------
    // 背景
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float t = now / 1000.0F;

        int left = this.leftPos;
        int top = this.topPos;
        int right = left + this.imageWidth;
        int bottom = top + this.imageHeight;

        // 1) 面板底：上紫下黑
        graphics.fillGradient(left, top, right, bottom, PANEL_TOP, PANEL_BOTTOM);

        // 2) 呼吸的外框：亮度随正弦缓慢变化
        float breathe = 0.55F + 0.45F * Mth.sin(t * 1.6F);
        int edge = withAlpha(ACCENT_SOFT, (int) (140 + 100 * breathe));
        border(graphics, left, top, right, bottom, edge);

        // 3) 四个角加一点亮，像悬浮的魔法框
        corner(graphics, left, top, 1, 1, ACCENT);
        corner(graphics, right - 1, top, -1, 1, ACCENT);
        corner(graphics, left, bottom - 1, 1, -1, ACCENT);
        corner(graphics, right - 1, bottom - 1, -1, -1, ACCENT);

        // 4) 星尘：绕面板中心缓慢旋转的光点
        drawStars(graphics, left + this.imageWidth / 2.0F, top + 62.0F, t);

        // 5) 输入槽
        drawSlot(graphics, now);
    }

    /** 一像素描边。 */
    private static void border(GuiGraphics g, int l, int t, int r, int b, int color) {
        g.fill(l, t, r, t + 1, color);          // 上
        g.fill(l, b - 1, r, b, color);          // 下
        g.fill(l, t, l + 1, b, color);          // 左
        g.fill(r - 1, t, r, b, color);          // 右
    }

    /** 角上的 L 形高光。 */
    private static void corner(GuiGraphics g, int x, int y, int dx, int dy, int color) {
        g.fill(x, y, x + dx * 6, y + dy, color);
        g.fill(x, y, x + dx, y + dy * 6, color);
    }

    /** 绕中心旋转的星尘。 */
    private void drawStars(GuiGraphics g, float cx, float cy, float t) {
        for (int i = 0; i < 9; i++) {
            double angle = t * 0.6 + i * (Math.PI * 2 / 9);
            // 半径也轻微起伏，看起来不像规整的圆环
            float radius = 46.0F + Mth.sin(t * 1.1F + i) * 5.0F;
            int sx = (int) (cx + Math.cos(angle) * radius * 1.35F);
            int sy = (int) (cy + Math.sin(angle) * radius * 0.62F);
            // 每颗星自己的闪烁相位
            float twinkle = 0.35F + 0.65F * Mth.sin(t * 2.3F + i * 1.7F);
            int alpha = (int) Mth.clamp(60 + 150 * twinkle, 0, 255);
            g.fill(sx, sy, sx + 2, sy + 2, withAlpha(STAR, alpha));
        }
    }

    /** 输入槽：凹槽 + 四角光点 + 内部反光。 */
    private void drawSlot(GuiGraphics g, long now) {
        int sx = this.leftPos + (this.imageWidth - 18) / 2;
        int sy = this.topPos + 60;

        // 外框（亮）
        g.fill(sx - 2, sy - 2, sx + 20, sy + 20, withAlpha(ACCENT_SOFT, 200));
        // 内部深色凹槽
        g.fill(sx - 1, sy - 1, sx + 19, sy + 19, 0xFF0B0417);
        // 顶部一层淡光，像被照亮
        g.fillGradient(sx - 1, sy - 1, sx + 19, sy + 9,
                withAlpha(ACCENT, 70), withAlpha(ACCENT, 0));

        // 四角光点，随时间微微呼吸
        float breathe = 0.5F + 0.5F * Mth.sin(now / 420.0F);
        int dot = withAlpha(GOLD, (int) (120 + 120 * breathe));
        g.fill(sx - 2, sy - 2, sx, sy, dot);
        g.fill(sx + 18, sy - 2, sx + 20, sy, dot);
        g.fill(sx - 2, sy + 18, sx, sy + 20, dot);
        g.fill(sx + 18, sy + 18, sx + 20, sy + 20, dot);
    }

    // ------------------------------------------------------------------
    // 文字与提示
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + this.imageWidth / 2;

        // 分隔线：把"槽"和"选项区"分开
        graphics.fill(this.leftPos + 14, this.topPos + 88,
                this.leftPos + this.imageWidth - 14, this.topPos + 89,
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
            // 点击前就要警告：这一步不可取消
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.smalltools.wash.warning")
                            .withStyle(ChatFormatting.RED),
                    centerX, this.topPos + 76, DANGER);
            hint = Component.translatable("screen.smalltools.wash.pick_entry");
            color = MUTED;
        } else {
            hint = Component.translatable("screen.smalltools.wash.insert");
        }
        graphics.drawCenteredString(this.font, hint, centerX, this.topPos + 92, color);

        // 锁定后明确提示石头已经没了
        if (this.locked) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.smalltools.wash.consumed")
                            .withStyle(ChatFormatting.YELLOW),
                    centerX, this.topPos + 76, OK);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component title = this.title;
        int centerX = this.imageWidth / 2;

        // 标题两侧各一颗小星，做出"咒文"的感觉
        graphics.drawString(this.font, "✦", centerX - this.font.width(title) / 2 - 10, 9, ACCENT, false);
        graphics.drawString(this.font, this.title, centerX - this.font.width(title) / 2, 8, TITLE, false);
        graphics.drawString(this.font, "✦",
                centerX + this.font.width(title) / 2 + 5, 9, ACCENT, false);
    }

    /** 给颜色替换 alpha（保留 RGB）。 */
    private static int withAlpha(int argb, int alpha) {
        int a = Mth.clamp(alpha, 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
