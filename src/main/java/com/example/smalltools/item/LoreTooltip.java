package com.example.smalltools.item;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 物品说明里"来源注释"的统一排布。
 *
 * <h2>为什么要单独抽出来</h2>
 *
 * <p>来源注释和"这个物品能干什么"是两种信息，挤在一起会读成一句。
 * 这里统一做两件事：</p>
 * <ul>
 *   <li><b>空两行</b>再写来源，把它和功能描述隔开；</li>
 *   <li>用<b>独立颜色</b>（暗紫斜体），不再和提示文字共用暗灰，
 *       否则在视觉上完全分不出哪句是传说、哪句是说明。</li>
 * </ul>
 *
 * <p>另外加了一层保险：如果传进来的 tooltip 末尾已经有空行，就不再重复空白，
 * 免得某些物品组合出四行空行。</p>
 */
public final class LoreTooltip {

    /** 来源注释与上方内容的间隔行数。 */
    private static final int GAP_LINES = 2;

    private LoreTooltip() {
    }

    /**
     * 在 tooltip 末尾追加来源注释。
     *
     * @param tooltip 目标列表
     * @param loreKey 来源文案的翻译键
     */
    public static void append(List<Component> tooltip, String loreKey) {
        // 先补空行，但如果已经以空行结尾就不再叠
        int trailingBlank = 0;
        for (int i = tooltip.size() - 1; i >= 0; i--) {
            if (tooltip.get(i).getString().isEmpty()) {
                trailingBlank++;
            } else {
                break;
            }
        }
        for (int i = trailingBlank; i < GAP_LINES; i++) {
            tooltip.add(CommonComponents.EMPTY);
        }

        tooltip.add(Component.translatable(loreKey)
                .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE,
                        net.minecraft.ChatFormatting.ITALIC));
    }
}
