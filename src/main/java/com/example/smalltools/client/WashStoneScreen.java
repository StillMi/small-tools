package com.example.smalltools.client;

import com.example.smalltools.menu.WashStoneMenu;
import com.example.smalltools.net.WashActionPayload;
import com.example.smalltools.net.WashStoneSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔洗炼界面。
 *
 * <p>布局：中间是输入槽，下面一排是"词条"或"候选"按钮。两种阶段不会同时出现 ——
 * 没选词条时列词条，选完词条后列三条候选。</p>
 *
 * <p>这个类只读服务端推过来的 {@link WashStoneSyncPayload}，自己不保存任何权威状态。</p>
 */
public class WashStoneScreen extends AbstractContainerScreen<WashStoneMenu> {

    private static final int ROW_HEIGHT = 20;
    private static final int ROW_WIDTH = 140;
    private static final int LIST_TOP = 96;

    /** 服务端同步过来的词条。 */
    private List<WashStoneSyncPayload.Entry> entries = List.of();
    /** 服务端同步过来的候选。 */
    private List<WashStoneSyncPayload.Entry> candidates = List.of();
    private int selected = -1;

    private final List<Button> rowButtons = new ArrayList<>();

    public WashStoneScreen(WashStoneMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
    }

    @Override
    protected void init() {
        super.init();
        rebuildRows();
        // 界面一打开就向服务端要一次状态
        PacketDistributor.sendToServer(
                new WashActionPayload(this.menu.containerId, WashActionPayload.KIND_REFRESH, 0));
    }

    /** 收到同步包后刷新按钮（由 ClientHooks 转进来）。 */
    public void accept(WashStoneSyncPayload payload) {
        if (payload.containerId() != this.menu.containerId) {
            return;
        }
        this.entries = payload.entries();
        this.candidates = payload.candidates();
        this.selected = payload.selected();
        rebuildRows();
    }

    private void rebuildRows() {
        for (Button button : this.rowButtons) {
            this.removeWidget(button);
        }
        this.rowButtons.clear();

        // 已选词条 → 展示候选；否则展示词条
        boolean showingCandidates = !this.candidates.isEmpty();
        List<WashStoneSyncPayload.Entry> rows = showingCandidates ? this.candidates : this.entries;

        int x = this.leftPos + (this.imageWidth - ROW_WIDTH) / 2;
        int y = this.topPos + LIST_TOP;

        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            WashStoneSyncPayload.Entry entry = rows.get(i);
            Component label = describe(entry);
            if (!showingCandidates && i == this.selected) {
                label = Component.literal("▸ ").append(label).withStyle(net.minecraft.ChatFormatting.YELLOW);
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

    /**
     * 把 {@code enchantment.minecraft.sharpness} 这样的 id 变成显示名。
     *
     * <p>等级用罗马数字追加，和原版附魔书一致。</p>
     */
    private static Component describe(WashStoneSyncPayload.Entry entry) {
        ResourceLocation id = entry.id();
        Component name = Component.translatable(
                "enchantment." + id.getNamespace() + "." + id.getPath());
        if (entry.level() <= 1) {
            return name;
        }
        return name.copy().append(" ").append(roman(entry.level()));
    }

    private static String roman(int value) {
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        if (value >= 0 && value < ones.length) {
            return ones[value];
        }
        return Integer.toString(value);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        Component hint;
        if (!this.candidates.isEmpty()) {
            hint = Component.translatable("screen.smalltools.wash.pick_candidate");
        } else if (!this.entries.isEmpty()) {
            hint = Component.translatable("screen.smalltools.wash.pick_entry");
        } else {
            hint = Component.translatable("screen.smalltools.wash.insert");
        }
        graphics.drawCenteredString(this.font, hint,
                this.leftPos + this.imageWidth / 2, this.topPos + 78, 0x808080);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 一层半透明底板，够看清就行，不额外画九宫格贴图
        graphics.fill(this.leftPos, this.topPos,
                this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xC0100018);
        graphics.fill(this.leftPos, this.topPos,
                this.leftPos + this.imageWidth, this.topPos + 1, 0xFF7B2FD6);
        // 输入槽的框
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
