package com.example.smalltools.item;

import com.example.smalltools.client.ClientHooks;
import com.example.smalltools.portal.CompanionTeleports;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

/**
 * 同伴传送石：右键选一位在线玩家，请求传送到他身边。
 *
 * <h2>和另外两颗传送石的区别</h2>
 *
 * <p>末影传送石 / 地牢传送石开的是<b>固定的门</b>，门在那里等着你走进去；
 * 这块石头没有门，它是<b>一次性的票</b>：对方点头，倒计时结束，你落在他旁边，石头消失。</p>
 *
 * <p>所以它不需要耐久 —— {@code MAX_USES} 那套是"开一次门扣一次"，
 * 这里是"用一次就没了"，直接用 {@code shrink(1)}。也因此它可以堆叠，
 * 带一组出门就等于带了一叠单程票。</p>
 *
 * <p>真正的状态机在 {@link CompanionTeleports}，这个类只负责"右键开界面"。</p>
 */
public class CompanionPortalStoneItem extends Item {

    public CompanionPortalStoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide()) {
            // 客户端只负责把选人界面弹出来；名单随后由服务端推过来
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientHooks.openCompanionScreen();
            }
        } else if (player instanceof ServerPlayer serverPlayer) {
            CompanionTeleports.sendRoster(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.smalltools.companion_stone")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.smalltools.companion_stone.hint")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        LoreTooltip.append(tooltip, "tooltip.smalltools.companion_stone.lore");
    }
}
