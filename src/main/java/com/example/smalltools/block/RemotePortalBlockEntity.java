package com.example.smalltools.block;

import com.example.smalltools.SmallTools;
import com.example.smalltools.portal.PortalCooldowns;
import com.example.smalltools.portal.PortalManager;
import com.example.smalltools.portal.PortalTeleports;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送门方块实体。
 *
 * <p>一座门由 2x3 = 6 个方块组成，每个方块各存一份相同的链接数据：
 * 自己这座门的原点与朝向、对面那座门的原点与朝向，以及<b>按人分开</b>的剩余次数。
 * 这样即使区块被卸载再加载，传送门也依然能工作。</p>
 *
 * <h2>次数是怎么算的（v1.07 起）</h2>
 *
 * <p>次数是<b>每个人各算各的</b>：</p>
 * <ul>
 *   <li>开门的人（创造者）有自己的计数器，初始 {@link #quota} 次；</li>
 *   <li>其他每个玩家也各自有一份，从满额开始；</li>
 *   <li>创造者把自己的次数用完之后，<b>整座门失效</b>；</li>
 *   <li>其他玩家用完自己的次数后只是<b>自己不能再传送</b>，门仍然开着，
 *       他们的个人次数不会影响门的寿命。</li>
 * </ul>
 *
 * <p>非玩家实体（掉落物、生物）共用一个"公共桶"（{@link #SHARED_ACTOR}），
 * 这样既能让掉落物照常穿过，又不会因为物品无限循环而让计数表无限膨胀。</p>
 *
 * <p>失效有三条路径：创造者次数用尽（{@link PortalManager#consumeUse}）、
 * 门面方块被破坏（{@link #onComponentBroken}）、
 * 黑曜石框架被破坏（{@link #serverTick} 里的每秒结构自检）。</p>
 */
public class RemotePortalBlockEntity extends BlockEntity {

    /** 非玩家实体共用的计数桶。 */
    public static final UUID SHARED_ACTOR = new UUID(0L, 0L);

    /** 结构自检间隔（tick）。 */
    private static final int CHECK_INTERVAL = 20;
    /** 连续几次自检都不完整才判定失效（防止建门/区块加载瞬间的抖动误拆）。 */
    private static final int BROKEN_CHECKS_BEFORE_INVALIDATE = 2;
    private static final Logger LOGGER = LogUtils.getLogger();

    private boolean configured;
    private int layoutVersion;
    private boolean entryStep;
    private BlockPos frameOrigin = BlockPos.ZERO;
    private Direction frameFront = Direction.SOUTH;
    private BlockPos partnerOrigin = BlockPos.ZERO;
    private Direction partnerFront = Direction.SOUTH;

    /** 开门的人；决定这座门什么时候失效。 */
    private UUID creator = SHARED_ACTOR;
    /** 每个人（含创造者）的满额次数。 */
    private int quota;
    /** 创造者还剩几次。 */
    private int creatorLeft;
    /** 其他玩家 / 公共桶还剩几次；没有条目时视为满额。 */
    private final Map<UUID, Integer> othersLeft = new HashMap<>();

    /** 仅用于错开自检，不需要存档。 */
    private int tickCounter;
    /** 连续几次自检发现结构不完整；结构恢复完整就清零。 */
    private int brokenChecks;

    public RemotePortalBlockEntity(BlockPos pos, BlockState state) {
        super(SmallTools.REMOTE_PORTAL_BE.get(), pos, state);
    }

    public void configure(BlockPos frameOrigin, Direction frameFront,
                          BlockPos partnerOrigin, Direction partnerFront,
                          UUID creator, int quota) {
        this.frameOrigin = frameOrigin;
        this.frameFront = frameFront;
        this.partnerOrigin = partnerOrigin;
        this.partnerFront = partnerFront;
        this.creator = creator;
        this.quota = quota;
        this.creatorLeft = quota;
        this.othersLeft.clear();
        this.configured = true;
        this.setChanged();
    }

    /** New geometry is persisted so older platforms still dismantle correctly. */
    public void setCompactLayout(boolean entryStep) {
        this.layoutVersion = 2;
        this.entryStep = entryStep;
        setChanged();
    }

    public boolean hasCompactLayout() { return layoutVersion >= 2; }
    public boolean hasEntryStep() { return entryStep; }

    /** 谁去用这座门，就按谁的 UUID 记账。 */
    public static UUID actorOf(Entity entity) {
        return entity instanceof ServerPlayer player ? player.getUUID() : SHARED_ACTOR;
    }

    public UUID getCreator() {
        return creator;
    }

    /** 这座门属于哪一组坐标；给结构自检判断"这一格的门面是不是我的"。 */
    public BlockPos getFrameOrigin() {
        return frameOrigin;
    }

    /** 门的朝向，和 {@link #getFrameOrigin()} 一起构成门的身份。 */
    public Direction getFrameFront() {
        return frameFront;
    }

    /** 这座门是不是"共享计数"（没有创造者）。 */
    private boolean isSharedBucket() {
        return SHARED_ACTOR.equals(creator);
    }

    /** 某个使用者在还能传送几次。 */
    public int leftFor(UUID actor) {
        // 共享桶：v1.06 迁移过来的门没有创造者，creator 是 SHARED_ACTOR。
        // 这时所有 actor 必须落到同一个计数上 —— 否则每个玩家都会从 othersLeft
        // 的缺省值拿到一份满额 quota，谁都用不完，门永远不会消失。
        if (isSharedBucket() || actor.equals(creator)) {
            return creatorLeft;
        }
        Integer left = othersLeft.get(actor);
        return left == null ? quota : left;
    }

    /** 记录某个使用者剩余次数；{@code 0} 会被明确写进表里，表示"已用完"。 */
    public void setLeftFor(UUID actor, int left) {
        if (isSharedBucket() || actor.equals(creator)) {
            creatorLeft = left;
        } else if (left >= quota) {
            // 回到满额等于没有条目：leftFor 的缺省值会给出一模一样的数字，
            // 留着只会让 othersLeft 随玩家数无限增长，最后每次存档都全量写盘。
            othersLeft.remove(actor);
        } else {
            othersLeft.put(actor, left);
        }
        this.setChanged();
    }

    /** 把自己这一份计数复制给别人（用于两端同步）。 */
    public void copyUsageFrom(RemotePortalBlockEntity source) {
        this.creator = source.creator;
        this.quota = source.quota;
        this.creatorLeft = source.creatorLeft;
        this.othersLeft.clear();
        this.othersLeft.putAll(source.othersLeft);
        this.setChanged();
    }

    /**
     * 服务端每 tick 调用。
     *
     * <p>正常情况下只有"主方块"（{@link PortalManager#masterPos}）真正执行结构自检，
     * 其余方块直接返回。但主方块可能被别的东西覆盖掉 —— 典型情况是别人在这座门的
     * 门洞位置建门，{@code placeStructure} 带着 {@code building = true} 把方块换成
     * 空气，{@code onRemove} 又被那道保护吞掉 —— 这时没人接力自检，剩下的门面会
     * 带着 {@code configured = true} 一直把人传到旧 partner。所以主方块一旦不在
     * （或者那一格换成了别的门的方块实体），当前方块就顶上。</p>
     *
     * <p>接管时用 {@link #checkPhase} 按坐标错开相位，几个方块不会同一 tick 一起跑。</p>
     */
    public void serverTick(ServerLevel level, BlockPos pos) {
        if (!configured) {
            return;
        }
        if (!pos.equals(PortalManager.masterPos(frameOrigin, frameFront)) && masterAlive(level)) {
            return;
        }
        tickCounter = (tickCounter + 1) % CHECK_INTERVAL;
        if (tickCounter != checkPhase(pos)) {
            return;
        }

        if (PortalManager.isTearingDown() || PortalManager.isBuilding()) {
            brokenChecks = 0;
            return;
        }
        // 次数已经用尽的门不该继续立着。正常流程里 consumeUse 会在归零那次就把门拆掉，
        // 但旧存档迁移（v1.06 的 Remaining 已经是 0）会留下这种门：
        // 它既不能用、也不会自己消失，会永远杵在原地。这里补一次清理。
        if (creatorLeft <= 0) {
            LOGGER.debug("[smalltools] dropping an expired portal at {}", frameOrigin);
            invalidatePair(level);
            return;
        }
        // 玩家把黑曜石框架拆了也算失效；但要连续两次都不完整才拆，
        // 免得建门或区块刚加载时的瞬时状态把好好的门拆掉
        if (PortalManager.isStructureIntact(level, frameOrigin, frameFront)) {
            brokenChecks = 0;
            return;
        }
        if (++brokenChecks >= BROKEN_CHECKS_BEFORE_INVALIDATE) {
            LOGGER.debug("[smalltools] frame no longer intact at {}, tearing down the pair", frameOrigin);
            invalidatePair(level);
        }
    }

    /** 自检相位：同一座门的几个方块按坐标错开，接管时不会同一 tick 一起跑。 */
    private static int checkPhase(BlockPos pos) {
        return Math.floorMod(pos.getX() * 31 + pos.getZ() * 17, CHECK_INTERVAL);
    }

    /** 主方块那一格是否还站着一个属于这座门的方块实体。 */
    private boolean masterAlive(ServerLevel level) {
        BlockPos master = PortalManager.masterPos(frameOrigin, frameFront);
        if (!level.hasChunkAt(master)) {
            // 没加载就当它还在：这里不能调 getBlockEntity，那会隐式同步加载，
            // 而且会让剩下几个方块抢着接管。
            return true;
        }
        return level.getBlockEntity(master) instanceof RemotePortalBlockEntity other
                && other.getFrameOrigin().equals(frameOrigin)
                && other.getFrameFront() == frameFront;
    }

    /**
     * 门面方块被破坏（挖掘 / 爆炸 / 活塞等）时调用，立即让整对门失效。
     */
    public void onComponentBroken(ServerLevel level, BlockPos brokenPos) {
        if (!configured) {
            return;
        }
        invalidatePair(level, brokenPos);
    }

    private void invalidatePair(ServerLevel level) {
        invalidatePair(level, null);
    }

    /**
     * @param skip 正在被 {@code setBlockState} 的那一格，拆除时跳过写入。
     *             玩家往门面格里放方块时，{@code onRemove} 会在同一个调用栈里回调过来，
     *             不跳过就会把玩家刚放下的方块再写成空气（方块被"吞"掉）。
     */
    private void invalidatePair(ServerLevel level, @Nullable BlockPos skip) {
        if (!configured) {
            return;
        }
        BlockPos frame = frameOrigin;
        Direction front = frameFront;
        BlockPos partner = partnerOrigin;
        Direction partnerDir = partnerFront;
        // 先拆、后改状态。PortalManager.invalidate 在 tearingDown / building 时会整段
        // return，先把 configured 置 false 就会留下一座"方块还立着、BE 已经哑了"的门：
        // serverTick 第一行直接返回、canAccept 恒 false，之后没有任何路径能让它自检或
        // 失效，只能手动挖。放在后面，失败时下一轮自检还会再试一次。
        // 重入由 PortalManager 的 tearingDown 挡住，不依赖这个字段。
        PortalManager.invalidate(level, frame, front, partner, partnerDir, skip);
        configured = false;
    }

    /**
     * 这座门现在能不能接这个实体。
     *
     * <p>{@link com.example.smalltools.block.RemotePortalBlock} 用它决定要不要把实体
     * 登记进原版的 {@code PortalProcessor}。</p>
     *
     * <p><b>有副作用，不是纯判定</b>：配额用完时会给玩家发一条 action bar 提示
     * （{@code message.smalltools.player_exhausted}），并让
     * {@link PortalCooldowns#tryHint} 往节流表里写一条记录。它每 tick 会被 6 个门面
     * 方块各调一次，刷屏靠 {@code PortalCooldowns} 的 {@code HINT_TICKS} 挡着，
     * 所以别在没有节流的地方循环调用它。</p>
     */
    public boolean canAccept(Entity entity) {
        if (!configured || isRemoved()) {
            return false;
        }
        // 骑乘状态不传送，免得把坐骑和乘客拆开
        if (entity.isPassenger() || entity.isVehicle()) {
            return false;
        }
        UUID actor = actorOf(entity);
        if (leftFor(actor) <= 0) {
            if (entity instanceof ServerPlayer player && PortalCooldowns.tryHint(entity)) {
                player.displayClientMessage(Component.translatable("message.smalltools.player_exhausted")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return false;
        }
        return !PortalCooldowns.isOnCooldown(entity);
    }

    /**
     * 原版 {@code PortalProcessor} 蓄满后回调到这里，把传送排进 tick 末尾执行。
     *
     * <p>不在这里直接传送：{@code getPortalDestination} 是在实体 tick 的
     * {@code handlePortal} 里被调用的，而传送要脱离 {@code handleMovePlayer} 的调用栈，
     * 否则会被客户端上报的旧坐标纠偏拉回来、次数却已经扣掉。</p>
     */
    public void requestTeleport(ServerLevel level, Entity entity) {
        PortalTeleports.enqueue(this, level, entity);
    }

    /**
     * 真正执行传送。由 {@link PortalTeleports} 在 tick 末尾调用，
     * 此时已经脱离 {@code handleMovePlayer} 的调用栈。
     *
     * <p>蓄力期间不设冷却：冷却要等传送真正成功之后才记（{@link PortalCooldowns#markPassed}），
     * 否则"排了队又没送成"会让玩家白等一个冷却周期。时长按原版下界门给
     * （玩家 10 tick、非玩家 300 tick）。</p>
     */
    public void executeTeleport(ServerLevel level, Entity entity) {
        // isRemoved()：排队之后这座门可能已经被拆了（拆门时先 setRemoved 再清方块），
        // 这时 configured 还可能是 true（拆门走的是另一份方块实体），
        // 不加这道检查就会把人送到一座已经不存在的门上
        if (!configured || isRemoved() || entity.isRemoved() || !entity.isAlive()) {
            return;
        }
        // 排队后实体可能已经被别处传走了（比如同一 tick 里进了另一座门），
        // 那就别把它再从新位置拽过来
        if (entity.level() != level) {
            return;
        }
        // 排队到执行之间可能已经被别的系统传送过、打上了冷却。entityInside 那道
        // 守卫只管"登记"，管不到已经排好队的这一次。
        if (entity.isOnPortalCooldown()) {
            return;
        }
        // canAccept 已经拒过骑乘，但排队到执行之间它可能刚骑上 / 被骑上；
        // 传送会把坐骑和乘客拆开
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        // 排队之后可能被别的门先传走了、或者次数刚被用完，这里再确认一次
        UUID actor = actorOf(entity);
        if (leftFor(actor) <= 0) {
            return;
        }

        // New arrivals stay over the inner half of the one-step entrance.
        // Legacy portals retain their wider platform spread.
        level.getChunk(partnerOrigin.getX() >> 4, partnerOrigin.getZ() >> 4);
        // 对端必须还站着"属于这一对门"的方块实体。只看有没有 BE 不够：重叠建门时
        // 那一格可能是另一座门的方块，按它的布局算落点会把人送到一个跟这对门无关的
        // 位置。一个都没有就直接放弃 —— 既别按 legacy 散布算坐标，也别去挖空气。
        RemotePortalBlockEntity partnerPortal = null;
        for (BlockPos pos : PortalManager.portalBlocks(partnerOrigin, partnerFront)) {
            if (level.getBlockEntity(pos) instanceof RemotePortalBlockEntity other
                    && other.getFrameOrigin().equals(partnerOrigin)
                    && other.getFrameFront() == partnerFront) {
                partnerPortal = other;
                break;
            }
        }
        if (partnerPortal == null) {
            LOGGER.debug("[smalltools] partner portal at {} is gone, dropping teleport of {}",
                    partnerOrigin, entity.getName().getString());
            return;
        }
        boolean compactTarget = partnerPortal.hasCompactLayout();
        double spreadX = ((entity.getUUID().hashCode() & 0xFF) / 255.0D - 0.5D)
                * (compactTarget ? 0.8D : 1.5D);
        double spreadZ = compactTarget ? 0.0D : (((entity.getUUID().hashCode() >>> 8) & 0xFF) / 255.0D - 0.5D) * 0.5D;
        double tx = PortalManager.localX(partnerOrigin, partnerFront, 2.0D + spreadX, 1.5D + spreadZ);
        double ty = partnerOrigin.getY() + 1.0D;
        double tz = PortalManager.localZ(partnerOrigin, partnerFront, 2.0D + spreadX, 1.5D + spreadZ);

        // 保证落点不是实心方块，避免卡墙/窒息
        PortalManager.clearLandingSpace(level, BlockPos.containing(tx, ty, tz));

        if (entity instanceof ServerPlayer serverPlayer) {
            serverPlayer.teleportTo(level, tx, ty, tz, partnerFront.toYRot(), 0.0F);
        } else {
            entity.teleportTo(tx, ty, tz);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();

        // 硬保险：位置没真的变过去，就绝不扣次数。
        // 这条路径以前出过事 —— 传送被 handleMovePlayer 的位置纠偏撤销，
        // 次数却已经扣了，玩家表现为"一直传送失败、次数却被耗光"。
        if (entity.position().distanceToSqr(tx, ty, tz) > 4.0D) {
            LOGGER.warn("[smalltools] teleport to {} did not take effect for {}, not consuming a use",
                    BlockPos.containing(tx, ty, tz), entity.getName().getString());
            return;
        }

        // 传送真的成功了：清掉蓄力，并按原版下界门的时长开始冷却
        PortalCooldowns.markPassed(entity);

        level.playSound(null, BlockPos.containing(tx, ty, tz), SoundEvents.PORTAL_TRAVEL,
                SoundSource.BLOCKS, 0.7F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL, tx, ty + 1.0D, tz, 32, 0.5D, 0.8D, 0.5D, 0.6D);

        // 记账：只有创造者的计数归零才会拆门
        int after = PortalManager.consumeUse(
                level, frameOrigin, frameFront, partnerOrigin, partnerFront, actor);

        if (entity instanceof ServerPlayer player) {
            if (after > 0) {
                player.displayClientMessage(Component.translatable("message.smalltools.remaining", after)
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            } else if (actor.equals(creator)) {
                player.displayClientMessage(Component.translatable("message.smalltools.expired")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            } else {
                player.displayClientMessage(Component.translatable("message.smalltools.player_exhausted")
                        .withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Configured", configured);
        tag.putInt("LayoutVersion", layoutVersion);
        tag.putBoolean("EntryStep", entryStep);
        tag.putLong("FrameOrigin", frameOrigin.asLong());
        tag.putString("FrameFront", frameFront.getName());
        tag.putLong("PartnerOrigin", partnerOrigin.asLong());
        tag.putString("PartnerFront", partnerFront.getName());
        tag.putUUID("Creator", creator);
        tag.putInt("Quota", quota);
        tag.putInt("CreatorLeft", creatorLeft);

        ListTag others = new ListTag();
        for (Map.Entry<UUID, Integer> entry : othersLeft.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", entry.getKey());
            one.putInt("Left", entry.getValue());
            others.add(one);
        }
        tag.put("OthersLeft", others);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        configured = tag.getBoolean("Configured");
        layoutVersion = tag.getInt("LayoutVersion");
        entryStep = tag.getBoolean("EntryStep");
        frameOrigin = BlockPos.of(tag.getLong("FrameOrigin"));
        frameFront = readDirection(tag.getString("FrameFront"));
        partnerOrigin = BlockPos.of(tag.getLong("PartnerOrigin"));
        partnerFront = readDirection(tag.getString("PartnerFront"));
        othersLeft.clear();

        if (tag.contains("CreatorLeft")) {
            // getUUID 对坏掉的 / 手改过的 NBT 会返回 null，不兜底的话下一次
            // saveAdditional 里的 putUUID 直接 NPE
            UUID stored = tag.contains("Creator") ? tag.getUUID("Creator") : null;
            creator = stored != null ? stored : SHARED_ACTOR;
            quota = Math.max(1, tag.getInt("Quota"));
            creatorLeft = tag.getInt("CreatorLeft");
            ListTag others = tag.getList("OthersLeft", Tag.TAG_COMPOUND);
            for (int i = 0; i < others.size(); i++) {
                CompoundTag one = others.getCompound(i);
                if (one.hasUUID("Id")) {
                    othersLeft.put(one.getUUID("Id"), one.getInt("Left"));
                }
            }
        } else if (tag.contains("Remaining")) {
            // v1.06 及更早的存档：整座门共用一个计数器。
            // 迁移成"共享计数"：creator 留空（SHARED_ACTOR），leftFor / setLeftFor
            // 会把所有 actor 都映射到 creatorLeft，所以"整座门共用一份次数"的
            // 行为不变，用尽时由 serverTick 的 creatorLeft <= 0 分支收尾。
            int legacy = Math.max(0, tag.getInt("Remaining"));
            creator = SHARED_ACTOR;
            quota = Math.max(1, legacy);
            creatorLeft = legacy;
        } else if (configured) {
            // 认得的两个计数键都没有：按"满额"兜底。留 0 的话 serverTick 的
            // creatorLeft <= 0 分支会在 20~40 tick 后把整对门当过期拆掉。
            creator = SHARED_ACTOR;
            quota = Math.max(1, quota);
            creatorLeft = quota;
        }
        // 注意：次数已经用尽的门在这里不能清 —— loadAdditional 拿不到 level。
        // 清理交给 serverTick，见那里的 creatorLeft <= 0 分支。
    }

    private static Direction readDirection(String name) {
        Direction direction = Direction.byName(name);
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.SOUTH;
    }
}
