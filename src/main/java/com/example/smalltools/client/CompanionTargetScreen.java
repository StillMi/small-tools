package com.example.smalltools.client;

import com.example.smalltools.net.CompanionRequestPayload;
import com.example.smalltools.net.CompanionRosterPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 同伴传送石的选人界面。
 *
 * <p>右键石头就会弹出来，但名单不是客户端自己翻 Tab 列表 —— 那份数据没有距离也没有维度，
 * 隐藏 Tab 的玩家还不在里面。名单由服务端算好推过来（{@link CompanionRosterPayload}），
 * 在它到达之前这里显示"正在获取玩家列表"。</p>
 *
 * <p>点一行就发请求，然后立刻关界面：接下来是对方的事，自己这边只等回应。</p>
 */
public class CompanionTargetScreen extends Screen {

    private static final int PANEL_W = 224;
    private static final int ROW_H = 22;
    private static final int VISIBLE_ROWS = 7;
    private static final int LIST_TOP = 52;
    private static final int FOOTER = 48;
    private static final int PANEL_H = LIST_TOP + VISIBLE_ROWS * ROW_H + FOOTER;

    private static final int PANEL_TOP = 0xD8140824;
    private static final int PANEL_BOTTOM = 0xE0080416;
    private static final int ACCENT = 0xFFB47CFF;
    private static final int ACCENT_SOFT = 0xFF8B5CF6;
    private static final int MUTED = 0xFF9A8AB8;
    private static final int GOLD = 0xFFFFD96A;
    private static final int ROW_BG = 0x50000000;
    private static final int ROW_HOVER = 0x50B47CFF;
    private static final int ROW_PICKED = 0x70B47CFF;

    private final List<CompanionRosterPayload.Entry> entries = new ArrayList<>();

    private boolean loaded;
    private int scroll;
    private int hovered = -1;

    private int leftPos;
    private int topPos;

    private CompanionTargetScreen() {
        super(Component.translatable("screen.smalltools.companion.title"));
    }

    /** 打开界面；由 {@link ClientHooks} 调用，只在客户端跑。 */
    public static void open() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new CompanionTargetScreen());
    }

    /** 服务端把名单推过来了。 */
    public void accept(CompanionRosterPayload payload) {
        this.entries.clear();
        this.entries.addAll(payload.players());
        this.loaded = true;
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, this.entries.size() - VISIBLE_ROWS);
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - PANEL_W) / 2;
        this.topPos = (this.height - PANEL_H) / 2;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.smalltools.companion.cancel"),
                        b -> this.onClose())
                .bounds(this.leftPos + (PANEL_W - 100) / 2, this.topPos + PANEL_H - 28, 100, 20)
                .build());
    }

    /* ------------------------------------------------------------------ */
    /* 绘制                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 面板放在背景阶段画：{@code Screen#render} 会先调这里、再画 renderables，
     * 所以“取消”按钮压在面板上面，而不是被面板盖住。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        // 面板：上深紫、下近黑，外面一圈会呼吸的紫色描边
        graphics.fillGradient(this.leftPos, this.topPos,
                this.leftPos + PANEL_W, this.topPos + PANEL_H, PANEL_TOP, PANEL_BOTTOM);

        float pulse = 0.55F + 0.45F * Mth.sin(System.currentTimeMillis() % 4000L / 4000.0F * 6.2831855F);
        border(graphics,
                this.leftPos, this.topPos,
                this.leftPos + PANEL_W, this.topPos + PANEL_H,
                withAlpha(ACCENT, (int) (70 + 90 * pulse)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + PANEL_W / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, this.topPos + 12, ACCENT);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.smalltools.companion.subtitle"),
                centerX, this.topPos + 28, MUTED);

        graphics.fill(this.leftPos + 14, this.topPos + 44,
                this.leftPos + PANEL_W - 14, this.topPos + 45, withAlpha(ACCENT_SOFT, 110));

        renderList(graphics, mouseX, mouseY);

        Component footer;
        if (!this.loaded) {
            footer = Component.translatable("screen.smalltools.companion.loading");
        } else if (this.entries.isEmpty()) {
            footer = Component.translatable("screen.smalltools.companion.empty");
        } else {
            footer = Component.translatable("screen.smalltools.companion.count", this.entries.size());
        }
        graphics.drawCenteredString(this.font, footer,
                centerX, this.topPos + LIST_TOP + VISIBLE_ROWS * ROW_H + 4,
                this.loaded && this.entries.isEmpty() ? GOLD : MUTED);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listLeft = this.leftPos + 10;
        int listRight = this.leftPos + PANEL_W - 10;
        int listTop = this.topPos + LIST_TOP;

        this.hovered = rowAt(mouseX, mouseY);

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = this.scroll + row;
            if (index >= this.entries.size()) {
                break;
            }
            int y = listTop + row * ROW_H;

            int background = index == this.hovered ? ROW_HOVER : ROW_BG;
            graphics.fill(listLeft, y, listRight, y + ROW_H - 2, background);
            if (index == this.hovered) {
                border(graphics, listLeft, y, listRight, y + ROW_H - 2, withAlpha(ACCENT, 190));
            }

            CompanionRosterPayload.Entry entry = this.entries.get(index);
            graphics.drawString(this.font, entry.name(),
                    listLeft + 8, y + 6, index == this.hovered ? ACCENT : 0xFFE8E0F5, false);

            Component where = entry.distance() < 0
                    ? Component.translatable("screen.smalltools.companion.other_dimension")
                    : Component.translatable("screen.smalltools.companion.distance", entry.distance());
            int width = this.font.width(where);
            graphics.drawString(this.font, where,
                    listRight - 8 - width, y + 6,
                    entry.sameLevel() ? MUTED : GOLD, false);
        }

        // 右边一条很细的滚动指示，玩家一多就知道还能往下翻
        if (maxScroll() > 0) {
            int trackTop = listTop;
            int trackBottom = listTop + VISIBLE_ROWS * ROW_H - 2;
            graphics.fill(listRight - 2, trackTop, listRight, trackBottom, withAlpha(ACCENT_SOFT, 60));
            float ratio = (float) VISIBLE_ROWS / this.entries.size();
            int height = Math.max(8, (int) ((trackBottom - trackTop) * ratio));
            int offset = (int) ((trackBottom - trackTop - height)
                    * ((float) this.scroll / maxScroll()));
            graphics.fill(listRight - 2, trackTop + offset, listRight, trackTop + offset + height,
                    withAlpha(ACCENT, 200));
        }
    }

    private static void border(GuiGraphics g, int l, int t, int r, int b, int color) {
        g.fill(l, t, r, t + 1, color);
        g.fill(l, b - 1, r, b, color);
        g.fill(l, t, l + 1, b, color);
        g.fill(r - 1, t, r, b, color);
    }

    private static int withAlpha(int argb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    /* ------------------------------------------------------------------ */
    /* 交互                                                                */
    /* ------------------------------------------------------------------ */

    /** 鼠标在列表里的第几行；不在列表上返回 -1。 */
    private int rowAt(double mouseX, double mouseY) {
        int listLeft = this.leftPos + 10;
        int listRight = this.leftPos + PANEL_W - 10;
        int listTop = this.topPos + LIST_TOP;
        if (mouseX < listLeft || mouseX >= listRight) {
            return -1;
        }
        if (mouseY < listTop || mouseY >= listTop + VISIBLE_ROWS * ROW_H) {
            return -1;
        }
        int row = (int) ((mouseY - listTop) / ROW_H);
        int index = this.scroll + row;
        return index < this.entries.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                choose(this.entries.get(index));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0.0D) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void choose(CompanionRosterPayload.Entry entry) {
        PacketDistributor.sendToServer(new CompanionRequestPayload(entry.id()));
        this.onClose();
    }

    /** 名单要等服务端推，所以不能暂停 —— 暂停了整合服务器就不会跑，包永远到不了。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
