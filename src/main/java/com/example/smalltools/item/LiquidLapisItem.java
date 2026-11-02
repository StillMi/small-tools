package com.example.smalltools.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 浓缩液态青金。
 *
 * <p>不再由工作台合成，而是<b>把青金石丢进熔炉烧出来</b>。
 * 在强化器界面里一颗一颗投入，为当前那次强化积攒经验。</p>
 *
 * <p>名字里的"液态"对应熔炼这道工序 —— 固态的青金石要先化开，
 * 才能在强化器里一点一点灌进附魔的缝隙。</p>
 */
public class LiquidLapisItem extends Item {

    public LiquidLapisItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.smalltools.liquid_lapis")
                .withStyle(ChatFormatting.GRAY));
        LoreTooltip.append(tooltip, "tooltip.smalltools.liquid_lapis.lore");
    }
}
