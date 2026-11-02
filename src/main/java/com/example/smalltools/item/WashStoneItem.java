package com.example.smalltools.item;

import com.example.smalltools.menu.WashStoneMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 附魔洗炼石。
 *
 * <p>右键打开洗炼界面：放进去一件<b>已附魔</b>的武器或工具，挑一个附魔词条，
 * 就可以把它换成随机出来的另一条附魔（等级沿用原来那条）。
 * 未附魔的物品放不进去。</p>
 *
 * <p>一次洗炼消耗一颗洗炼石：只有<b>替换真正完成</b>时才会扣掉手里的石头，
 * 中途关界面不会损失。</p>
 */
public class WashStoneItem extends Item {

    public WashStoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            // 把拿石头的那只手带过去，替换成功时才能准确扣掉这一颗
            serverPlayer.openMenu(new SimpleMenuProvider(
                            (id, inventory, p) -> new WashStoneMenu(id, inventory, hand),
                            Component.translatable("container.smalltools.wash_stone")),
                    // 必须写一份额外数据：客户端要靠它知道是哪只手，
                    // 不写的话客户端读到的是空缓冲区，readEnum 会直接抛异常
                    buf -> buf.writeEnum(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.smalltools.wash_stone")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.smalltools.wash_stone.hint")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
