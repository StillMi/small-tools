package com.example.smalltools.client;

import com.example.smalltools.menu.WashStoneMenu;
import com.example.smalltools.net.WashActionPayload;
import com.example.smalltools.net.WashStoneSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔洗炼界面。
 *
 * <p>中间是输入槽，下面一排是"词条"或"候选"按钮，两种阶段不会同时出现。</p>
 *
 * <p>显示名直接用服务端算好的 {@link Component}，客户端不拼翻译键 ——
 * 拼出来的键对模组附魔不成立，会显示成一整串英文标识。</p>
 */
public class WashStoneScreen extends AbstractContainerScreen<WashStoneMenu> {

    private static final int ROW_HEIGHT = 20;
    private static final int ROW_WIDTH = 150;
    private static final int LIST_TOP = 100;

    private List<WashStoneSyncPayload.Entry> entries = List.of();
    private List<WashStoneSyncPayload.Entry> candidates = List.of();
    private int selected = -1;
    private boolean locked;

    private final List<Button> rowButtons = new ArrayList<>();

    public WashStoneScreen(WashStoneMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 232;
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
                label = Component.literal("▸ ").append(label).withStyle(ChatFormatting.YELLOW);
            }
            Button button = Button.builder(label, b -> onRowClicked(showingCandidates, index))
                    .bounds(x, y + i * ROW_HEIGHT, ROW_WIDTH, 18)
                    .build();
            this.addRenderableWidget(button);
            this.rowButtons.add(button);
        }
    }

    private void onRowClicked(boolean showingCandidates, int index) {
        int kind = showingCandidates ? WashActionPayload.KIND_CANDIDATE : WashActionPayload.KIND_ENTRY;
        PacketDistributor.sendToServer(new WashActionPayload(this.menu.containerId, kind, index));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.leftPos + this.imageWidth / 2;
        Component hint;
        int color = 0x808080;

        if (!this.candidates.isEmpty()) {
            hint = Component.translatable("screen.smalltools.wash.pick_candidate");
        } else if (this.locked) {
            hint = Component.translatable("screen.smalltools.wash.no_candidate");
            color = 0xC04040;
        } else if (!this.entries.isEmpty()) {
            hint = Component.translatable("screen.smalltools.wash.pick_entry");
            // 点击前就要警告：这一步不可取消
            Component warn = Component.translatable("screen.smalltools.wash.warning")
                    .withStyle(ChatFormatting.RED);
            graphics.drawCenteredString(this.font, warn, centerX, this.topPos + 78, 0xFF5555);
            color = 0x808080;
        } else {
            hint = Component.translatable("screen.smalltools.wash.insert");
        }
        graphics.drawCenteredString(this.font, hint, centerX, this.topPos + 90, color);

        // 已锁定时明确提示：石头已消耗
        if (this.locked) {
            Component consumed = Component.translatable("screen.smalltools.wash.consumed")
                    .withStyle(ChatFormatting.YELLOW);
            graphics.drawCenteredString(this.font, consumed, centerX, this.topPos + 78, 0xFFD060);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(this.leftPos, this.topPos,
                this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xC0100018);
        graphics.fill(this.leftPos, this.topPos,
                this.leftPos + this.imageWidth, this.topPos + 1, 0xFF7B2FD6);
        int slotX = this.leftPos + 79;
        int slotY = this.topPos + 59;
        graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, 0xFF8B8B8B);
        graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0xFF373737);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title,
                this.imageWidth / 2 - this.font.width(this.title) / 2, 6, 0xE0C8FF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
