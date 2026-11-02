package com.example.smalltools.client;

import com.example.smalltools.SmallTools;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 客户端专用初始化。
 *
 * <p>用 {@code @EventBusSubscriber(value = Dist.CLIENT)} 标注，NeoForge 在专用服务器上
 * 不会加载这个类，所以引用客户端类（{@link ChannelHud}、{@code GuiGraphics} 等）
 * 不会导致服务端崩溃。</p>
 */
@EventBusSubscriber(modid = SmallTools.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(com.example.smalltools.SmallTools.WASH_STONE_MENU.get(), WashStoneScreen::new);
        event.register(com.example.smalltools.SmallTools.ENHANCER_MENU.get(), EnhancerScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR,
                ResourceLocation.fromNamespaceAndPath(SmallTools.MODID, "channel_bar"),
                new ChannelHud());
    }
}
