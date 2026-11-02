package com.example.smalltools;

import com.example.smalltools.block.RemotePortalBlock;
import com.example.smalltools.block.RemotePortalBlockEntity;
import com.example.smalltools.item.AbstractPortalStoneItem;
import com.example.smalltools.item.DungeonPortalStoneItem;
import com.example.smalltools.item.PortalStoneItem;
import com.example.smalltools.net.SetTargetPayload;
import com.example.smalltools.portal.PortalTeleports;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * StillMi 的小工具（整合模组）。
 *
 * <p>当前收录：末影传送石、地牢传送石，以及它们共用的传送门方块。
 * 两块石头都在自己的创造模式标签页 {@link #TAB} 里，不跟原版工具类混在一起。</p>
 *
 * <p>传送门失效（次数用尽 / 任意组成方块被破坏）时，两端整座门
 * 一起被完全清除，不留遗迹。每次新开一对门，该玩家上一对还没失效的门会自动消散。
 * 传送石本身最多只能开门 {@link PortalStoneItem#MAX_USES} 次，用完后物品消失。</p>
 */
@Mod(SmallTools.MODID)
public class SmallTools {
    public static final String MODID = "smalltools";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);


    /**
     * 这块传送石绑定的目标坐标。
     *
     * <p>存在 <b>ItemStack</b> 上，所以每块石头各自记各自的 —— 一块石头只能绑一个地点，
     * 但同一个人可以有好几块石头分别指到不同地方。组件不存在时退回配置文件里的
     * {@code targetX / targetY / targetZ}，老存档和没绑过的石头照常能用。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> PORTAL_TARGET =
            DATA_COMPONENTS.registerComponentType(
                    "portal_target",
                    builder -> builder
                            .persistent(BlockPos.CODEC)
                            .networkSynchronized(BlockPos.STREAM_CODEC));

    /**
     * 末影传送石：8 黑曜石 + 1 末影珍珠合成。
     * durability(N) 让"N 次开门"直接复用原版耐久机制，用满后物品自动销毁。
     */
    public static final DeferredItem<PortalStoneItem> PORTAL_STONE = ITEMS.registerItem(
            "portal_stone",
            PortalStoneItem::new,
            new Item.Properties().durability(AbstractPortalStoneItem.MAX_USES).rarity(Rarity.RARE));

    /**
     * 地牢传送石：配方是 8 块金锭 + 1 颗末影珍珠（见 data/smalltools/recipe）。
     * 和末影传送石共用引导 / 冷却 / 耐久机制，只是另一端落在附近的地牢里。
     */
    public static final DeferredItem<DungeonPortalStoneItem> DUNGEON_PORTAL_STONE = ITEMS.registerItem(
            "dungeon_portal_stone",
            DungeonPortalStoneItem::new,
            new Item.Properties().durability(AbstractPortalStoneItem.MAX_USES).rarity(Rarity.EPIC));

    /**
     * 传送门方块：无碰撞、自带光源、可破坏（破坏即让整对门失效），不掉落任何东西。
     * 硬度给得很低，方便玩家随时手动拆掉门。
     */
    public static final DeferredBlock<RemotePortalBlock> REMOTE_PORTAL = BLOCKS.registerBlock(
            "remote_portal",
            RemotePortalBlock::new,
            BlockBehaviour.Properties.of()
                    .noCollission()
                    .strength(0.3F)
                    .lightLevel(state -> 11)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.GLASS));

    /** 传送门方块实体：保存这一端 / 另一端的坐标与剩余可用次数。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RemotePortalBlockEntity>> REMOTE_PORTAL_BE =
            BLOCK_ENTITIES.register("remote_portal",
                    () -> BlockEntityType.Builder
                            .of(RemotePortalBlockEntity::new, REMOTE_PORTAL.get())
                            .build(null));

    /**
     * 本模组自己的创造模式标签页，图标用末影传送石。
     *
     * <p>必须声明在两个物品之后：{@code icon} 和 {@code displayItems} 都会引用它们，
     * 写在前面会触发「非法前向引用」。</p>
     */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.smalltools"))
                    .icon(() -> new ItemStack(PORTAL_STONE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(PORTAL_STONE.get());
                        output.accept(DUNGEON_PORTAL_STONE.get());
                    })
                    .build());

    public SmallTools(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

        modEventBus.addListener(this::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 传送不能在 entityInside 里就地执行（会被 handleMovePlayer 的位置纠偏拉回去），
        // 统一排到 tick 末尾，见 PortalTeleports
        NeoForge.EVENT_BUS.addListener(PortalTeleports::onServerTickPost);
    }

    /** 客户端改坐标 → 服务端落库。 */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                SetTargetPayload.TYPE, SetTargetPayload.STREAM_CODEC, SetTargetPayload::handle);
    }
}
