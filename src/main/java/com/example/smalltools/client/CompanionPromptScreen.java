package com.example.smalltools.client;

import com.example.smalltools.net.CompanionPromptPayload;
import com.example.smalltools.net.CompanionReplyPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 收到别人的传送请求时弹出来的确认框。
 *
 * <p>只有"同意"和"拒绝"两个出口，按 ESC 等同于拒绝 —— 不能什么都不做就把框关掉，
 * 否则请求会一直挂着到超时，对方只能在那边干等。</p>
 *
 * <p>倒计时是本地算的，和服务端的超时各自独立：本地先到 0 就发一个拒绝并关掉，
 * 服务端到点也会把请求清掉并发一个 {@code cancel} 过来（那时界面早关了，无害）。</p>
 */
public class CompanionPromptScreen extends Screen {

    private static final int PANEL_W = 214;
    private static final int PANEL_H = 122;

    private static final int PANEL_TOP = 0xD8140824;
    private static final int PANEL_BOTTOM = 0xE0080416;
    private static final int ACCENT = 0xFFB47CFF;
    private static final int ACCENT_SOFT = 0xFF8B5CF6;
    private static final int MUTED = 0xFF9A8AB8;
    private static final int GOLD = 0xFFFFD96A;
    private static final int GOOD = 0xFF7CE8A4;

    private final int requestId;
    private final Component sender;
    private final int totalSeconds;
    private final int distance;

    private int ticksLeft;

    /** 已经回过包（同意 / 拒绝）或者被服务端撤下，关界面时不要再发一次。 */
    private boolean settled;

    private int leftPos;
    private int topPos;

    public CompanionPromptScreen(CompanionPromptPayload payload) {
        super(Component.translatable("screen.smalltools.companion.prompt.title"));
        this.requestId = payload.requestId();
        this.sender = payload.sender();
        this.totalSeconds = payload.seconds();
        this.distance = payload.distance();
        this.ticksLeft = payload.seconds() * 20;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - PANEL_W) / 2;
        this.topPos = (this.height - PANEL_H) / 2;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.smalltools.companion.accept"),
                        b -> answer(true))
                .bounds(this.leftPos + 14, this.topPos + PANEL_H - 30, 90, 20)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.smalltools.companion.reject"),
                        b -> answer(false))
                .bounds(this.leftPos + PANEL_W - 104, this.topPos + PANEL_H - 30, 90, 20)
                .build());
    }

    /** 每秒掉一格，到 0 就自己退场。 */
    @Override
    public void tick() {
        if (this.ticksLeft > 0) {
            this.ticksLeft--;
        }
        if (this.ticksLeft <= 0 && !this.settled) {
            // 本地超时也回一个“拒绝”：服务端 TPS 低于 20 时它的超时会更长，
            // 不发的话请求会一直挂到服务端超时，对方只能干等。
            // 服务端那边请求已经没了的话，reply 本来就静默忽略，重复发是安全的。
            answer(false);
        }
    }

    /**
     * 面板放在背景阶段画：{@code Screen#render} 会先调这里、再画 renderables，
     * 所以“同意/拒绝”按钮压在面板上面，而不是被面板盖住。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.fillGradient(this.leftPos, this.topPos,
                this.leftPos + PANEL_W, this.topPos + PANEL_H, PANEL_TOP, PANEL_BOTTOM);

        float pulse = 0.55F + 0.45F * Mth.sin(System.currentTimeMillis() % 3000L / 3000.0F * 6.2831855F);
        border(graphics, this.leftPos, this.topPos,
                this.leftPos + PANEL_W, this.topPos + PANEL_H,
                withAlpha(ACCENT, (int) (70 + 90 * pulse)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + PANEL_W / 2;

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.smalltools.companion.prompt.line", this.sender),
                centerX, this.topPos + 16, ACCENT);

        Component where = this.distance < 0
                ? Component.translatable("screen.smalltools.companion.other_dimension")
                : Component.translatable("screen.smalltools.companion.prompt.distance", this.distance);
        graphics.drawCenteredString(this.font, where, centerX, this.topPos + 34,
                this.distance < 0 ? GOLD : MUTED);

        graphics.fill(this.leftPos + 14, this.topPos + 52,
                this.leftPos + PANEL_W - 14, this.topPos + 53, withAlpha(ACCENT_SOFT, 110));

        // 剩余秒数用一条会缩短的进度槽表示，比干看数字直观
        int seconds = (this.ticksLeft + 19) / 20;
        int barLeft = this.leftPos + 14;
        int barRight = this.leftPos + PANEL_W - 14;
        int filled = this.totalSeconds <= 0
                ? 0
                : (int) ((barRight - barLeft) * (this.ticksLeft / (float) (this.totalSeconds * 20)));
        graphics.fill(barLeft, this.topPos + 66, barRight, this.topPos + 72, 0x50000000);
        if (filled > 0) {
            graphics.fill(barLeft, this.topPos + 66, barLeft + filled, this.topPos + 72,
                    withAlpha(seconds <= 5 ? GOLD : GOOD, 200));
        }

        graphics.drawCenteredString(this.font,
                Component.translatable("screen.smalltools.companion.prompt.remaining", seconds),
                centerX, this.topPos + 80, seconds <= 5 ? GOLD : MUTED);
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

    private void answer(boolean accept) {
        if (this.settled) {
            return;
        }
        this.settled = true;
        PacketDistributor.sendToServer(new CompanionReplyPayload(this.requestId, accept));
        this.onClose();
    }

    /** 服务端把请求撤下了（超时 / 对方取消）。 */
    public void closeFromServer() {
        this.settled = true;
        this.onClose();
    }

    /** ESC 等同于拒绝。 */
    @Override
    public void onClose() {
        if (!this.settled) {
            this.settled = true;
            PacketDistributor.sendToServer(new CompanionReplyPayload(this.requestId, false));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
