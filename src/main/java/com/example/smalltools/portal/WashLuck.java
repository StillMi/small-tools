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
 * <p>每次洗炼随机增加 30%~45%，累积到 100% 时<b>下一次</b>洗炼直接产出最高等级附魔，
 * 然后清零。</p>
 *
 * <p>换算成万分之一（basis point，0~10000）存整数，避免浮点累积误差：
 * 每次加 3000~4500，达到 10000 即触发。</p>
 *
 * <h2>为什么单独做一个文件</h2>
 *
 * <p>运气是<b>跨门/跨物品</b>的玩家属性，不属于某一件装备，
 * 所以放在 {@code DataStorage} 里而不是物品 NBT。写在主世界的
 * {@code data/smalltools_luck.dat}。</p>
 */
public final class WashLuck extends SavedData {

    private static final String DATA_NAME = "smalltools_luck";
    /** 满值：这里用万分之一，10000 = 100%。 */
    public static final int FULL = 10_000;
    /** 每次最少加多少（30%）。 */
    public static final int MIN_GAIN = 3_000;
    /** 每次最多加多少（45%）。 */
    public static final int MAX_GAIN = 4_500;

    private final Map<UUID, Integer> luck = new HashMap<>();

    public WashLuck() {
    }

    /** 当前运气值（0~10000）。 */
    public int get(ServerPlayer player) {
        return luck.getOrDefault(player.getUUID(), 0);
    }

    /** 运气是否已经满了（下一次必定最高等级）。 */
    public boolean isFull(ServerPlayer player) {
        return get(player) >= FULL;
    }

    /**
     * 洗炼后推进运气。
     *
     * <p>如果这次已经用了满运气，就清零；否则累加一个随机的 30%~45%。</p>
     *
     * @param usedFull 这次洗炼是否消耗了满运气
     * @param random   随机源
     * @return 推进后的新值
     */
    public int advance(ServerPlayer player, boolean usedFull, java.util.Random random) {
        if (usedFull) {
            luck.put(player.getUUID(), 0);
            setDirty();
            return 0;
        }
        int gain = MIN_GAIN + random.nextInt(MAX_GAIN - MIN_GAIN + 1);
        // 不用 clamp：允许涨到超过 FULL，下次判定依然成立，用完再清零
        int next = get(player) + gain;
        luck.put(player.getUUID(), next);
        setDirty();
        return next;
    }

    /** 百分比文本，用于提示。 */
    public static String percent(int value) {
        return String.format(java.util.Locale.ROOT, "%d%%", value / 100);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        luck.forEach((id, value) -> map.putInt(id.toString(), value));
        tag.put("Luck", map);
        return tag;
    }

    public static WashLuck load(CompoundTag tag, HolderLookup.Provider registries) {
        WashLuck data = new WashLuck();
        CompoundTag map = tag.getCompound("Luck");
        for (String key : map.getAllKeys()) {
            try {
                data.luck.put(UUID.fromString(key), map.getInt(key));
            } catch (IllegalArgumentException ignored) {
                // 存档里有坏 UUID 就跳过，不影响其他人
            }
        }
        return data;
    }

    /** 取全服唯一实例（挂在主世界 DataStorage 上）。 */
    public static WashLuck get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WashLuck::new, WashLuck::load), DATA_NAME);
    }
}
