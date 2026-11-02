package com.example.smalltools.portal;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每个玩家的"洗炼运气"。
 *
 * <h2>规则</h2>
 *
 * <p>每次洗炼随机增加 30%~45%，累积到 100% 后<b>下一次</b>洗炼直接产出最高等级附魔，
 * 然后清零。</p>
 *
 * <h2>为什么用"待生效标记"而不是只看数值</h2>
 *
 * <p>一开始是"数值 >= 10000 就算满"。问题出在时序上：满值那一次洗炼结束后要清零，
 * 但玩家在界面上看到的、以及 {@code isFull()} 判断的，都是<b>推进之后</b>的值。
 * 只要某次推进把数值推过 10000、那一次又没用满值（例如玩家是分批点的），
 * 后面就会出现"显示 130% 但候选不是最高等级"的错位。</p>
 *
 * <p>现在拆成两个概念：</p>
 * <ul>
 *   <li>{@code progress}：0~{@link #FULL} 的进度，仅用于显示；</li>
 *   <li>{@code armed}：<b>下一次是否必定最高等级</b>。进度攒满时置位，
 *       真正被使用后才清除。这样"满没满"和"显示多少"永远不会互相打架。</li>
 * </ul>
 */
public final class WashLuck extends SavedData {

    private static final String DATA_NAME = "smalltools_luck";
    /** 满值，万分之一刻度：10000 = 100%。 */
    public static final int FULL = 10_000;
    /** 每次最少加多少（30%）。 */
    public static final int MIN_GAIN = 3_000;
    /** 每次最多加多少（45%）。 */
    public static final int MAX_GAIN = 4_500;

    /** 单个玩家的运气状态。 */
    private static final class State {
        int progress;
        boolean armed;
    }

    private final Map<UUID, State> states = new HashMap<>();

    public WashLuck() {
    }

    private State state(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), id -> new State());
    }

    /** 当前进度（0~10000），用于显示。 */
    public int get(ServerPlayer player) {
        return state(player).progress;
    }

    /**
     * 下一次是否必定产出最高等级。
     *
     * <p>只看 {@code armed}，不看进度 —— 这样即使进度因为四舍五入显示成 100%
     * 但还没真正攒满，也不会误判。</p>
     */
    public boolean isArmed(ServerPlayer player) {
        return state(player).armed;
    }

    /**
     * 洗炼时调用：结算"这次是否用满值"，并推进进度。
     *
     * @param consumedArmed 这次洗炼是否消耗了满值
     * @param random        随机源
     * @return 推进后的进度
     */
    public int advance(ServerPlayer player, boolean consumedArmed, java.util.Random random) {
        State s = state(player);

        if (consumedArmed) {
            // 用掉了满值：进度清零，待生效标记撤销
            s.progress = 0;
            s.armed = false;
            setDirty();
            return 0;
        }

        int gain = MIN_GAIN + random.nextInt(MAX_GAIN - MIN_GAIN + 1);
        s.progress = Math.min(FULL, s.progress + gain);
        // 攒满即置位：下一次一定最高等级
        if (s.progress >= FULL) {
            s.armed = true;
        }
        setDirty();
        return s.progress;
    }

    /** 百分比文本。 */
    public static String percent(int value) {
        return String.format(java.util.Locale.ROOT, "%d%%", Math.min(100, value / 100));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        states.forEach((id, s) -> {
            CompoundTag one = new CompoundTag();
            one.putInt("Progress", s.progress);
            one.putBoolean("Armed", s.armed);
            map.put(id.toString(), one);
        });
        tag.put("Luck", map);
        return tag;
    }

    public static WashLuck load(CompoundTag tag, HolderLookup.Provider registries) {
        WashLuck data = new WashLuck();
        CompoundTag map = tag.getCompound("Luck");
        for (String key : map.getAllKeys()) {
            try {
                UUID id = UUID.fromString(key);
                CompoundTag one = map.getCompound(key);
                State s = new State();
                s.progress = one.getInt("Progress");
                s.armed = one.getBoolean("Armed");
                // 兼容旧存档：只有进度、没有 Armed 字段时按进度补判
                if (!one.contains("Armed") && s.progress >= FULL) {
                    s.armed = true;
                }
                data.states.put(id, s);
            } catch (IllegalArgumentException ignored) {
                // 坏 UUID 跳过，不影响其他人
            }
        }
        return data;
    }

    /** 取全服唯一实例。 */
    public static WashLuck get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WashLuck::new, WashLuck::load), DATA_NAME);
    }
}
