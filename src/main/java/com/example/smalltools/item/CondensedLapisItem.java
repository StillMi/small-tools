package com.example.smalltools.item;

import com.example.smalltools.SmallTools;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 凝造青金：附魔强化的消耗材料。
 *
 * <p>本身没有任何功能，只在强化器界面里被扣掉。名字取"凝造"是因为它把
 * 散乱的附魔之力凝成可以注入装备的形态 —— 配方是青金石围住一块原石，
 * 正好对应"凝结"这个意象。</p>
 */
public class CondensedLapisItem extends Item {

    public CondensedLapisItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.smalltools.condensed_lapis")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.smalltools.condensed_lapis.lore")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
