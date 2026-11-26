package com.example.smalltools.client;

import net.minecraft.world.InteractionHand;

/**
 * 通用代码调用客户端功能的唯一入口。
 *
 * <p>{@code AbstractPortalStoneItem#use} 是两边都会跑的方法，里面要"潜行右键打开坐标界面"。
 * 界面本身（{@link PortalTargetScreen}）会碰 {@code Minecraft.getInstance()} 这类
 * 只有客户端才有的东西，所以隔了一层：</p>
 *
 * <ul>
 *   <li>调用点先用 {@code FMLEnvironment.dist == Dist.CLIENT} 判断，
 *       专用服务器上这个方法体根本不会执行；</li>
 *   <li>这个类本身<b>不加</b> {@code @OnlyIn}。加了反而更容易在类校验阶段被提前加载，
 *       不加就是普通的类，只有在真被调用时 JVM 才会去解析它引用的客户端类型。</li>
 * </ul>
 *
 * <p>也就是说：真正危险的那一步（加载 {@link PortalTargetScreen}）只会在
 * "客户端 + 玩家真的潜行右键了" 的时候发生。</p>
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    /** 洗炼界面收到服务端推来的状态，转交给当前界面。 */
    public static void onWashSync(com.example.smalltools.net.WashStoneSyncPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen
                instanceof WashStoneScreen screen) {
            screen.accept(payload);
        }
    }

    /** 强化界面收到服务端状态。 */
    public static void onEnhancerSync(com.example.smalltools.net.EnhancerSyncPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof EnhancerScreen screen) {
            screen.accept(payload);
        }
    }

    /** 打开目标坐标输入界面。只应在客户端调用。 */
    public static void openTargetScreen(InteractionHand hand) {
        PortalTargetScreen.open(hand);
    }

    /** 打开同伴传送石的选人界面。只应在客户端调用。 */
    public static void openCompanionScreen() {
        CompanionTargetScreen.open();
    }

    /** 选人界面收到服务端推来的在线玩家名单。 */
    public static void onCompanionRoster(com.example.smalltools.net.CompanionRosterPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen
                instanceof CompanionTargetScreen screen) {
            screen.accept(payload);
        }
    }

    /**
     * 有人请求传送到自己身边，或者服务端把那个请求撤下了。
     *
     * <p>{@code cancel} 只有一种情况需要处理：确认框正开着就把它关掉；
     * 已经关掉了就什么都不做。</p>
     */
    public static void onCompanionPrompt(com.example.smalltools.net.CompanionPromptPayload payload) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (payload.cancel()) {
            if (minecraft.screen instanceof CompanionPromptScreen screen) {
                screen.closeFromServer();
            }
            return;
        }
        minecraft.setScreen(new CompanionPromptScreen(payload));
    }
}
