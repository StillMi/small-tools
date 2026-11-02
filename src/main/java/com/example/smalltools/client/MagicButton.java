package com.example.smalltools.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 洗炼界面里用的选项按钮，走魔法紫而不是原版灰白贴图。
 *
 * <p>{@link Button} 自己不画背景，背景由父类 {@code AbstractButton.renderWidget}
 * 通过 {@code blitSprite(SPRITES...)} 贴上去。这里整段覆盖掉，只留文字渲染，
 * 底色、描边、悬停泛光都自己画，才能和紫色面板协调。</p>
 *
 * <p>三种状态：</p>
 * <ul>
 *   <li>普通：暗紫底 + 一圈淡紫描边；</li>
 *   <li>悬停 / 聚焦：底色变亮，描边加一圈外发光；</li>
 *   <li>选中（列词条阶段的那一条）：金色描边。</li>
 * </ul>
 */
public class MagicButton extends Button {

    private static final int BASE = 0xCC2A1152;
    private static final int HOVER = 0xEE4A1E8C;
    private static final int EDGE = 0xFF8B5CF6;
    private static final int EDGE_HOVER = 0xFFC4A0FF;
    private static final int EDGE_SELECTED = 0xFFFFD96A;
    private static final int TEXT = 0xFFE8D8FF;
    private static final int TEXT_HOVER = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF8A7AA0;

    private final boolean highlighted;

    private MagicButton(Builder builder, boolean highlighted) {
        super(builder);
        this.highlighted = highlighted;
    }

    /** 用 Builder 构建；{@code highlighted} 为选中项。 */
    public static Button create(Builder builder, boolean highlighted) {
        return builder.build(b -> new MagicButton(b, highlighted));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();
        long now = System.currentTimeMillis();

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();

        // 底色
        graphics.fill(x, y, x + w, y + h, hovered ? HOVER : BASE);

        // 顶部一道浅光，做出"玻璃"感
        graphics.fillGradient(x, y, x + w, y + h / 2,
                withAlpha(0xFF6A3FB0, hovered ? 110 : 70), withAlpha(0xFF6A3FB0, 0));

        // 描边
        int edgeColor;
        if (highlighted) {
            edgeColor = EDGE_SELECTED;
        } else if (hovered) {
            // 悬停时描边呼吸，让"可以被点"这件事更明显
            float breathe = 0.6F + 0.4F * Mth.sin(now / 260.0F);
            edgeColor = withAlpha(EDGE_HOVER, (int) (170 + 85 * breathe));
        } else {
            edgeColor = EDGE;
        }
        graphics.fill(x, y, x + w, y + 1, edgeColor);
        graphics.fill(x, y + h - 1, x + w, y + h, edgeColor);
        graphics.fill(x, y, x + 1, y + h, edgeColor);
        graphics.fill(x + w - 1, y, x + w, y + h, edgeColor);

        // 悬停时在外侧再加一圈半透明光晕
        if (hovered) {
            int glow = withAlpha(EDGE_HOVER, 70);
            graphics.fill(x - 1, y - 1, x + w + 1, y, glow);
            graphics.fill(x - 1, y + h, x + w + 1, y + h + 1, glow);
            graphics.fill(x - 1, y, x, y + h, glow);
            graphics.fill(x + w, y, x + w + 1, y + h, glow);
        }

        // 文字：覆盖的是 renderString（不是 renderScrollingString —— 那个是子类的私有细节）
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        int textColor = !this.active ? TEXT_DISABLED : (hovered ? TEXT_HOVER : TEXT);
        this.renderString(graphics, font, textColor);
    }

    /**
     * 居中画文字。
     *
     * <p>父类原本会调用 {@code renderScrollingString} 做跑马灯，
     * 但这里选项名都很短，居中静态显示更稳，也不会因为滚动而抖。</p>
     */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        int textWidth = font.width(this.getMessage());
        int x = this.getX() + (this.getWidth() - textWidth) / 2;
        int y = this.getY() + (this.getHeight() - 8) / 2 + 1;
        graphics.drawString(font, this.getMessage(), x, y, color, true);
    }

    private static int withAlpha(int argb, int alpha) {
        int a = Mth.clamp(alpha, 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
