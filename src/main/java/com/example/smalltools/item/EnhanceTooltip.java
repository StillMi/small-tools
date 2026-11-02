package com.example.smalltools.item;

import com.example.smalltools.SmallTools;
import com.example.smalltools.menu.EnhancerMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 把强化次数与进度直接写在物品说明里的能力。
 *
 * <p>这是<b>物品自带</b>的信息，不属于任何玩家 —— 换人、掉落、放箱子，
 * 打开看到的都是同一份数值。之所以要显式显示，是因为"次数固定在装备上"
 * 这件事如果不写出来，玩家只会觉得莫名其妙不能强化了。</p>
 */
public final class EnhanceTooltip {

    private EnhanceTooltip() {
    }

    /** 在附魔说明之后追加强化状态。由各物品的 appendHoverText 调用。 */
    public static void append(ItemStack stack, List<Component> tooltip) {
        int used = stack.getOrDefault(SmallTools.ENHANCE_COUNT.get(), 0);
        if (used <= 0) {
            return;
        }
        int max = EnhancerMenu.MAX_ENHANCES;
        boolean full = used >= max;

        tooltip.add(Component.translatable("tooltip.smalltools.enhanced",
                        used, max)
                .withStyle(full ? ChatFormatting.RED : ChatFormatting.AQUA));

        if (full) {
            tooltip.add(Component.translatable("tooltip.smalltools.enhanced.maxed")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            return;
        }

        // 还没满时显示下一次的代价，方便直接判断值不值得
        int xp = EnhancerMenu.xpForStep(used + 1);
        int lapis = EnhancerMenu.lapisForStep(used + 1);
        tooltip.add(Component.translatable("tooltip.smalltools.enhanced.next", xp, lapis)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
