package com.example.smalltools.portal;

import com.example.smalltools.Config;
import com.example.smalltools.item.CompanionPortalStoneItem;
import com.example.smalltools.net.CompanionPromptPayload;
import com.example.smalltools.net.CompanionRosterPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 同伴传送石的服务端流程：请求 → 对方同意 → 倒计时 → 传送 → 扣石头。
 *
 * <h2>为什么整条流程都要自己管状态</h2>
 *
 * <p>这不是"站进门里就被传走"那种被动触发器，而是一段跨越两个玩家的、可以中间反悔的约定。
 * 请求可能超时、对方可能拒绝、两个人可能在任何一步离线、请求者可能把石头丢掉，
 * 所以每一次状态迁移都要重新校验一遍，不能只在开头查一次。</p>
 *
 * <h2>顺序：先传送，后扣石头</h2>
 *
 * <p>扣石头放在传送<b>成功之后</b>。任何一步失败（对方下线、脚下没有能站人的地方）
 * 都只取消，不扣。这样"传送失败还把石头吃掉"这种最让人火大的情况不存在。</p>
 *
 * <p>另外，真正的传送发生在 {@link ServerTickEvent.Post} —— 和 {@link PortalTeleports}
 * 同一个理由：不能在 {@code handleMovePlayer} 的调用栈里改位置，否则会被位置纠偏拉回去。</p>
 */
public final class CompanionTeleports {

    private static final int TICKS_PER_SECOND = 20;

    /** 还没被回应的请求。{@code remaining} 按服务端 tick 递减。 */
    private record Request(int id, UUID sender, UUID target, int remaining) {
    }

    /** 已经同意、正在倒计时的传送。 */
    private record Countdown(UUID sender, UUID target, int remaining) {
    }

    private static final List<Request> REQUESTS = new ArrayList<>();
    private static final List<Countdown> COUNTDOWNS = new ArrayList<>();

    private static int nextId = 1;

    private CompanionTeleports() {
    }

    /* ------------------------------------------------------------------ */
    /* 名单                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 把可选的玩家名单发给请求者。
     *
     * <p>客户端自己也有 Tab 列表，但那上面没有距离、没有维度，隐藏 Tab 的玩家还不在里面，
     * 所以由服务端算好再发。整个名单按"同维度近的排前面"排序，跨维度的排最后。</p>
     */
    public static void sendRoster(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<CompanionRosterPayload.Entry> entries = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == player || other.isSpectator()) {
                continue;
            }
            boolean sameLevel = other.level() == player.level();
            // 跨维度时距离没有意义，用 -1 占位，客户端据此显示"另一个维度"
            int distance = sameLevel
                    ? (int) Math.round(Math.sqrt(player.distanceToSqr(other)))
                    : -1;
            entries.add(new CompanionRosterPayload.Entry(
                    other.getDisplayName().copy(), other.getUUID(), distance, sameLevel));
        }
        // 同维度在前、近的在前、同距离按名字排。写成一个 lambda 而不是链式
        // Comparator，是因为 thenComparing 的两个重载在这个位置容易推不出类型。
        entries.sort((a, b) -> {
            if (a.sameLevel() != b.sameLevel()) {
                return a.sameLevel() ? -1 : 1;
            }
            int left = a.distance() < 0 ? Integer.MAX_VALUE : a.distance();
            int right = b.distance() < 0 ? Integer.MAX_VALUE : b.distance();
            if (left != right) {
                return Integer.compare(left, right);
            }
            return a.name().getString().compareToIgnoreCase(b.name().getString());
        });

        PacketDistributor.sendToPlayer(player, new CompanionRosterPayload(List.copyOf(entries)));
    }

    /* ------------------------------------------------------------------ */
    /* 请求                                                                */
    /* ------------------------------------------------------------------ */

    /** 请求者点了某位玩家。所有条件都在这里重查一遍。 */
    public static void request(ServerPlayer sender, UUID targetId) {
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        if (sender.isSpectator()) {
            actionBar(sender, "message.smalltools.companion.spectator", ChatFormatting.RED);
            return;
        }
        // 手上没有石头就不认这个包
        if (findStone(sender) == null) {
            actionBar(sender, "message.smalltools.companion.stone_gone", ChatFormatting.RED);
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || target.isSpectator()) {
            actionBar(sender, "message.smalltools.companion.no_player", ChatFormatting.RED);
            return;
        }
        if (target == sender) {
            actionBar(sender, "message.smalltools.companion.self", ChatFormatting.RED);
            return;
        }
        // 一个人同时只能有一个出去的请求、一个进来的请求，免得界面互相顶掉
        if (hasOutgoing(sender.getUUID()) || isCounting(sender.getUUID())) {
            actionBar(sender, "message.smalltools.companion.busy_self", ChatFormatting.RED);
            return;
        }
        if (hasIncoming(target.getUUID()) || isCounting(target.getUUID())) {
            actionBar(sender, "message.smalltools.companion.busy_target", ChatFormatting.RED);
            return;
        }

        int id = nextId++;
        REQUESTS.add(new Request(id, sender.getUUID(), target.getUUID(),
                Config.COMPANION_REQUEST_SECONDS.get() * TICKS_PER_SECOND));

        PacketDistributor.sendToPlayer(target, new CompanionPromptPayload(
                id, sender.getDisplayName().copy(), Config.COMPANION_REQUEST_SECONDS.get(),
                distanceOf(sender, target), false));

        actionBar(sender, Component.translatable("message.smalltools.companion.sent",
                target.getDisplayName()), ChatFormatting.LIGHT_PURPLE);
        target.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
    }

    /** 目标玩家点了同意 / 拒绝。 */
    public static void reply(ServerPlayer target, int id, boolean accept) {
        Request matched = null;
        for (Request request : REQUESTS) {
            if (request.id() == id && request.target().equals(target.getUUID())) {
                matched = request;
                break;
            }
        }
        // 请求已经不在了（超时 / 已经回过一次）就当作没发生
        if (matched == null) {
            return;
        }
        REQUESTS.remove(matched);

        MinecraftServer server = target.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer sender = server.getPlayerList().getPlayer(matched.sender());
        if (sender == null) {
            actionBar(target, "message.smalltools.companion.sender_gone", ChatFormatting.RED);
            return;
        }

        if (!accept) {
            actionBar(target, Component.translatable("message.smalltools.companion.you_rejected",
                    sender.getDisplayName()), ChatFormatting.GRAY);
            actionBar(sender, Component.translatable("message.smalltools.companion.rejected",
                    target.getDisplayName()), ChatFormatting.RED);
            sender.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0F, 0.8F);
            return;
        }

        // 同意也不能叠在另一条倒计时上：B 可以先向 C 发请求、再对 A 的请求点同意，
        // 所以 accept 这边必须和 request 一样复查一次
        if (isCounting(sender.getUUID()) || isCounting(target.getUUID())) {
            cancelBoth(sender, target, Component.translatable("message.smalltools.companion.busy_countdown"));
            return;
        }

        int seconds = Config.COMPANION_DELAY_SECONDS.get();
        // 即使配成 0 秒也走一次 1 tick 的倒计时，让传送落在 ServerTickEvent.Post，
        // 而不是在这个包的处理调用栈里
        int ticks = seconds <= 0 ? 1 : seconds * TICKS_PER_SECOND;
        COUNTDOWNS.add(new Countdown(sender.getUUID(), target.getUUID(), ticks));

        if (seconds > 0) {
            actionBar(target, Component.translatable("message.smalltools.companion.accepted_target",
                    sender.getDisplayName(), seconds), ChatFormatting.LIGHT_PURPLE);
            actionBar(sender, Component.translatable("message.smalltools.companion.accepted",
                    target.getDisplayName(), seconds), ChatFormatting.LIGHT_PURPLE);
        }
        sender.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.6F);
        target.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0F, 1.6F);
    }

    /* ------------------------------------------------------------------ */
    /* tick                                                                */
    /* ------------------------------------------------------------------ */

    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        // 1. 请求超时 / 任意一方离线
        for (int i = REQUESTS.size() - 1; i >= 0; i--) {
            Request request = REQUESTS.get(i);
            ServerPlayer sender = server.getPlayerList().getPlayer(request.sender());
            ServerPlayer target = server.getPlayerList().getPlayer(request.target());

            // 离线的一方等不到超时：立刻撤掉，别让对方在这一段时间里一直是 busy_target。
            // 不新增事件监听，顺手在 tick 里做。
            if (sender == null || target == null) {
                REQUESTS.remove(i);
                if (target != null) {
                    // 请求者掉线：撤窗 + 说明原因
                    PacketDistributor.sendToPlayer(target, new CompanionPromptPayload(
                            request.id(), Component.empty(), 0, -1, true));
                    actionBar(target, Component.translatable("message.smalltools.companion.cancelled",
                            Component.translatable("message.smalltools.companion.sender_gone")),
                            ChatFormatting.RED);
                }
                if (sender != null) {
                    // 目标掉线：直接告诉请求者，不用干等 30 秒
                    actionBar(sender, "message.smalltools.companion.target_gone", ChatFormatting.GRAY);
                }
                continue;
            }

            int left = request.remaining() - 1;
            if (left > 0) {
                REQUESTS.set(i, new Request(request.id(), request.sender(), request.target(), left));
                continue;
            }
            REQUESTS.remove(i);
            // cancel = true 让客户端把弹窗撤下去
            PacketDistributor.sendToPlayer(target, new CompanionPromptPayload(
                    request.id(), Component.empty(), 0, -1, true));
            actionBar(sender, Component.translatable("message.smalltools.companion.expired",
                    target.getDisplayName()), ChatFormatting.GRAY);
        }

        // 2. 倒计时
        for (int i = COUNTDOWNS.size() - 1; i >= 0; i--) {
            Countdown countdown = COUNTDOWNS.get(i);
            ServerPlayer sender = server.getPlayerList().getPlayer(countdown.sender());
            ServerPlayer target = server.getPlayerList().getPlayer(countdown.target());

            if (sender == null || target == null) {
                COUNTDOWNS.remove(i);
                if (sender != null) {
                    actionBar(sender, Component.translatable("message.smalltools.companion.cancelled",
                            Component.translatable("message.smalltools.companion.target_gone")),
                            ChatFormatting.RED);
                }
                if (target != null) {
                    actionBar(target, Component.translatable("message.smalltools.companion.cancelled",
                            Component.translatable("message.smalltools.companion.sender_gone")),
                            ChatFormatting.RED);
                }
                continue;
            }

            int left = countdown.remaining() - 1;
            if (left <= 0) {
                COUNTDOWNS.remove(i);
                execute(sender, target);
                continue;
            }
            COUNTDOWNS.set(i, new Countdown(countdown.sender(), countdown.target(), left));

            // 每秒提示一次，别每 tick 都刷屏
            if (left % TICKS_PER_SECOND == 0) {
                int seconds = left / TICKS_PER_SECOND;
                actionBar(sender, Component.translatable("message.smalltools.companion.countdown_self",
                        seconds, target.getDisplayName()), ChatFormatting.LIGHT_PURPLE);
                actionBar(target, Component.translatable("message.smalltools.companion.countdown_target",
                        sender.getDisplayName(), seconds), ChatFormatting.LIGHT_PURPLE);
            }
        }
    }

    /** 服务器关了就把状态清掉，免得同一个客户端进程里开新世界时还带着上一局的请求。 */
    public static void onServerStopped(ServerStoppedEvent event) {
        REQUESTS.clear();
        COUNTDOWNS.clear();
        nextId = 1;
    }

    /* ------------------------------------------------------------------ */
    /* 真正传送                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 最后一次校验 + 执行传送 + 扣石头。
     *
     * <p>这里每一条失败都对应一句明确的话，不会静悄悄地什么都不发生 ——
     * 倒计时都数到 0 了却没反应，是最难受的体验。</p>
     */
    private static void execute(ServerPlayer sender, ServerPlayer target) {
        InteractionHand hand = findStone(sender);
        if (hand == null) {
            actionBar(sender, "message.smalltools.companion.stone_gone", ChatFormatting.RED);
            actionBar(target, Component.translatable("message.smalltools.companion.cancelled",
                    Component.translatable("message.smalltools.companion.stone_gone")),
                    ChatFormatting.RED);
            return;
        }
        if (!sender.isAlive() || !target.isAlive()) {
            cancelBoth(sender, target, Component.translatable("message.smalltools.companion.dead"));
            return;
        }
        // 倒计时期间可以切旁观，request 时查过一次不代表现在还成立
        if (sender.isSpectator() || target.isSpectator()) {
            cancelBoth(sender, target, Component.translatable("message.smalltools.companion.spectator"));
            return;
        }
        if (!(target.level() instanceof ServerLevel level)) {
            cancelBoth(sender, target, Component.translatable("message.smalltools.companion.bad_level"));
            return;
        }
        Vec3 landing = findLanding(level, target);
        if (landing == null) {
            actionBar(sender, Component.translatable("message.smalltools.companion.cancelled",
                    Component.translatable("message.smalltools.companion.no_ground")), ChatFormatting.RED);
            actionBar(target, "message.smalltools.companion.no_ground_target", ChatFormatting.RED);
            return;
        }

        ItemStack stone = sender.getItemInHand(hand);

        // 先传送
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        sender.teleportTo(level, landing.x, landing.y, landing.z, target.getYRot(), 0.0F);

        // teleportTo 是 void：别的模组的事件可以取消它，维度切换也可能失败。
        // 没真的站到 landing 就不扣石头、也不报“已抵达”。
        if (sender.serverLevel() != level || sender.position().distanceToSqr(landing) >= 4.0D) {
            cancelBoth(sender, target, Component.translatable("message.smalltools.companion.teleport_failed"));
            return;
        }

        sender.setDeltaMovement(Vec3.ZERO);
        sender.resetFallDistance();
        level.sendParticles(ParticleTypes.PORTAL,
                landing.x, landing.y + 1.0, landing.z, 48, 0.4, 0.8, 0.4, 0.3);
        level.playSound(null, landing.x, landing.y, landing.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 传送成功了才扣石头
        stone.shrink(1);

        actionBar(sender, Component.translatable("message.smalltools.companion.arrived",
                target.getDisplayName()), ChatFormatting.LIGHT_PURPLE);
        actionBar(target, Component.translatable("message.smalltools.companion.arrived_target",
                sender.getDisplayName()), ChatFormatting.LIGHT_PURPLE);
    }

    /**
     * 在目标身边找一格能站人的地方。
     *
     * <p>先找半径 1 的八格，再逐圈往外扩到 {@link #LANDING_RADIUS}。
     * 之所以不直接落在目标脚下：两个人叠在同一格会被原版的推挤逻辑弹开，
     * 有概率把人挤进墙里或者挤下悬崖。</p>
     */
    private static final int LANDING_RADIUS = 3;

    private static Vec3 findLanding(ServerLevel level, ServerPlayer target) {
        BlockPos base = target.blockPosition();
        for (int radius = 1; radius <= LANDING_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只走这一圈的边框，里面的格子上一圈已经查过
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos pos = base.offset(dx, dy, dz);
                        if (standable(level, pos) && hasHeadroom(level, pos)) {
                            return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 这一格能不能站人：本身是空的、不在水里、下面顶面结实、不是树叶。 */
    private static boolean standable(ServerLevel level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!here.getFluidState().isEmpty() || !here.canBeReplaced()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.getFluidState().isEmpty() || floor.is(BlockTags.LEAVES)) {
            return false;
        }
        return floor.isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * 脚那一格上方能不能再放下一格人：不是液体、可以被替换（空气、草、雪）。
     *
     * <p>{@link #standable} 查的是“脚 + 下面的地板”，这里查的是“头”——
     * 玩家 1.8 格高，必须两格都空着。之前这里又调了一次 {@code standable(pos.above())}，
     * 而 {@code pos} 已经是空气，空气不可能给上面当结实地板，所以永远返回 false。</p>
     */
    private static boolean hasHeadroom(ServerLevel level, BlockPos pos) {
        BlockState head = level.getBlockState(pos.above());
        return head.getFluidState().isEmpty() && head.canBeReplaced();
    }

    /* ------------------------------------------------------------------ */
    /* 小工具                                                              */
    /* ------------------------------------------------------------------ */

    /** 找手上拿着同伴传送石的那只手，先主手后副手。 */
    private static InteractionHand findStone(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof CompanionPortalStoneItem) {
                return hand;
            }
        }
        return null;
    }

    private static boolean hasOutgoing(UUID sender) {
        for (Request request : REQUESTS) {
            if (request.sender().equals(sender)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIncoming(UUID target) {
        for (Request request : REQUESTS) {
            if (request.target().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCounting(UUID player) {
        for (Countdown countdown : COUNTDOWNS) {
            if (countdown.sender().equals(player) || countdown.target().equals(player)) {
                return true;
            }
        }
        return false;
    }

    private static int distanceOf(ServerPlayer a, ServerPlayer b) {
        return a.level() == b.level()
                ? (int) Math.round(Math.sqrt(a.distanceToSqr(b)))
                : -1;
    }

    /** 失败时给双方各发一条“传送取消：原因”。 */
    private static void cancelBoth(ServerPlayer sender, ServerPlayer target, Component reason) {
        Component text = Component.translatable("message.smalltools.companion.cancelled", reason);
        actionBar(sender, text, ChatFormatting.RED);
        actionBar(target, text, ChatFormatting.RED);
    }

    private static void actionBar(ServerPlayer player, String key, ChatFormatting color) {
        actionBar(player, Component.translatable(key), color);
    }

    private static void actionBar(ServerPlayer player, Component text, ChatFormatting color) {
        player.displayClientMessage(text.copy().withStyle(color), true);
    }
}
