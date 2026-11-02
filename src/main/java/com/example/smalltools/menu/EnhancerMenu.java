package com.example.smalltools.menu;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.CondensedLapisItem;
import com.example.smalltools.item.EnchantEnhancerItem;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 附魔强化界面。
 *
 * <h2>规则</h2>
 *
 * <ol>
 *   <li><b>消耗递增</b>：第 n 次强化需要 {@code 1 + n} 个凝造青金
 *       （第 1 次 1 个、第 2 次 2 个……），强化次数记在物品上。</li>
 *   <li><b>补足到 3 条</b>：物品只有 1 个附魔时，前两次强化会先补随机附魔，
 *       直到凑满 3 条。补进来的附魔固定 <b>1 级</b>。</li>
 *   <li><b>升级有上限</b>：附魔等级不超过它自身的 {@code getMaxLevel()}。
 *       随机到的那条已经满级时，本次换成强化另一条可升级的。</li>
 *   <li><b>全部满级时不能强化</b>：界面会明确提示，不扣材料。</li>
 * </ol>
 *
 * <h2>强化次数存在哪</h2>
 *
 * <p>记在<b>物品自身</b>的 {@code ENHANCE_COUNT} 数据组件上，因为同一件装备
 * 拿给不同的人强化，成本应该延续下去；放在玩家身上会让"换个人就便宜"成为漏洞。</p>
 */
public class EnhancerMenu extends AbstractContainerMenu {

    public static final int SLOT_ITEM = 0;

    public static final int PANEL_W = 200;
    /** 面板高度：比洗炼界面高，专门留给进度条和按钮。 */
    public static final int PANEL_H = 268;
    public static final int SLOT_U = (PANEL_W - 18) / 2;
    public static final int SLOT_V = 62;
    public static final int INV_U = (PANEL_W - 9 * 18) / 2;
    /** 物品栏放到底部，和按钮之间留足空隙，避免重叠。 */
    public static final int INV_V = 226;
    /** 强化按钮位置（面板内坐标）。 */
    public static final int BUTTON_V = 196;

    /** 补足目标：物品上最多先凑到这么多条附魔。 */
    public static final int FILL_TO = 3;

    /** 每件装备最多强化几次。 */
    public static final int MAX_ENHANCES = 5;

    /**
     * 第 n 次强化的凝造青金消耗（n 从 1 开始）。
     *
     * <p>曲线 1 / 3 / 6 / 10 / 15：差值是 2、3、4、5，递增幅度本身也在变大，
     * 越往后越舍不得。之前写的是 {@code 1 + count}（1/2/3/4/5），
     * 五次加起来才 15 个，压不住节奏。</p>
     */
    public static int costForStep(int step) {
        return switch (step) {
            case 1 -> 1;
            case 2 -> 3;
            case 3 -> 6;
            case 4 -> 10;
            default -> 15;
        };
    }

    private final InteractionHand hand;
    private final Player menuPlayer;
    private final ServerPlayer owner;
    private final Random random = new Random();

    private final SimpleContainer container = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            EnhancerMenu.this.onChanged();
        }
    };

    /** 最近一次操作的结果，用于界面提示。 */
    private String lastResult = "";

    public EnhancerMenu(int id, Inventory inventory, InteractionHand hand) {
        super(SmallTools.ENHANCER_MENU.get(), id);
        this.hand = hand;
        this.menuPlayer = inventory.player;
        this.owner = inventory.player instanceof ServerPlayer sp ? sp : null;

        this.addSlot(new Slot(container, SLOT_ITEM, SLOT_U + 1, SLOT_V + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return isEnhanceable(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, INV_U + 1 + col * 18, INV_V + 1));
        }
    }

    /** 能不能拿来强化：必须是已附魔、且不是附魔书。 */
    public static boolean isEnhanceable(ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.ENCHANTED_BOOK)) {
            return false;
        }
        return !stack.getEnchantments().isEmpty();
    }

    private void onChanged() {
        lastResult = "";
        broadcastChanges();
        sync();
    }

    // ------------------------------------------------------------------
    // 成本与状态
    // ------------------------------------------------------------------

    /** 这件物品已经强化过几次。 */
    public int enhanceCount(ItemStack stack) {
        return stack.getOrDefault(SmallTools.ENHANCE_COUNT.get(), 0);
    }

    /** 下一次强化需要几个凝造青金；已到上限时返回 0。 */
    public int costFor(ItemStack stack) {
        int count = enhanceCount(stack);
        if (count >= MAX_ENHANCES) {
            return 0;
        }
        return costForStep(count + 1);
    }

    /** 这件装备的强化次数是否已经用满。 */
    public boolean isExhausted(ItemStack stack) {
        return enhanceCount(stack) >= MAX_ENHANCES;
    }

    /** 还能强化几次。 */
    public int remaining(ItemStack stack) {
        return Math.max(0, MAX_ENHANCES - enhanceCount(stack));
    }

    /** 玩家手上的凝造青金总数。 */
    public int lapisCount() {
        int total = 0;
        for (int i = 0; i < menuPlayer.getInventory().getContainerSize(); i++) {
            ItemStack s = menuPlayer.getInventory().getItem(i);
            if (s.getItem() instanceof CondensedLapisItem) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** 这件物品上是否还有可以升级的附魔。 */
    public boolean hasUpgradable(ItemStack stack) {
        for (Holder<Enchantment> holder : stack.getEnchantments().keySet()) {
            if (canUpgrade(stack, holder)) {
                return true;
            }
        }
        return false;
    }

    private boolean canUpgrade(ItemStack stack, Holder<Enchantment> holder) {
        int level = stack.getEnchantments().getLevel(holder);
        return level < holder.value().getMaxLevel();
    }

    /** 是否还需要补足附魔条数。 */
    public boolean needsFill(ItemStack stack) {
        return stack.getEnchantments().size() < FILL_TO;
    }

    /** 当前物品还能不能强化（不考虑材料够不够）。 */
    public boolean canEnhance(ItemStack stack) {
        if (!isEnhanceable(stack)) {
            return false;
        }
        if (isExhausted(stack)) {
            return false;      // 五次用完，彻底停手
        }
        return needsFill(stack) || hasUpgradable(stack);
    }

    public String lastResult() {
        return lastResult;
    }

    public InteractionHand hand() {
        return hand;
    }

    // ------------------------------------------------------------------
    // 强化
    // ------------------------------------------------------------------

    /**
     * 执行一次强化。
     *
     * @return 是否成功
     */
    public boolean enhance() {
        ItemStack stack = container.getItem(SLOT_ITEM);
        if (!isEnhanceable(stack)) {
            return false;
        }
        // 强化必须在服务端做；用玩家所在的 level 取附魔注册表
        if (!(menuPlayer.level() instanceof ServerLevel level)) {
            return false;
        }

        if (isExhausted(stack)) {
            lastResult = "screen.smalltools.enhancer.exhausted";
            sync();
            return false;
        }
        if (!canEnhance(stack)) {
            lastResult = "screen.smalltools.enhancer.maxed";
            sync();
            return false;
        }

        int cost = costFor(stack);
        if (lapisCount() < cost) {
            lastResult = "screen.smalltools.enhancer.no_material";
            sync();
            return false;
        }

        // 先规划这一步要做什么，成功后再扣材料，避免扣了料没生效
        boolean filled = false;
        if (needsFill(stack)) {
            filled = tryFill(stack, level);
            if (!filled) {
                // 补不出新附魔（可选池为空）时退回升级
                filled = false;
            }
        }
        if (!filled) {
            if (!tryUpgrade(stack)) {
                lastResult = "screen.smalltools.enhancer.maxed";
                sync();
                return false;
            }
        }

        // 扣材料
        if (!consumeLapis(cost)) {
            return false;
        }

        // 记一次强化
        stack.set(SmallTools.ENHANCE_COUNT.get(), enhanceCount(stack) + 1);

        lastResult = filled
                ? "screen.smalltools.enhancer.filled"
                : "screen.smalltools.enhancer.upgraded";

        level.playSound(null, menuPlayer.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.PLAYERS, 1.0F, 1.3F);

        broadcastChanges();
        sync();
        return true;
    }

    /**
     * 补一条随机附魔（1 级）。
     *
     * @return 是否补成功
     */
    private boolean tryFill(ItemStack stack, ServerLevel level) {
        ItemEnchantments existing = stack.getEnchantments();
        List<Holder<Enchantment>> pool = new ArrayList<>();
        for (Holder<Enchantment> holder : level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).holders().toList()) {
            if (existing.getLevel(holder) > 0) {
                continue;                       // 已经有了
            }
            if (holder.is(EnchantmentTags.CURSE)) {
                continue;                       // 不补诅咒
            }
            if (!stack.supportsEnchantment(holder)) {
                continue;                       // 必须能附在这件物品上
            }
            pool.add(holder);
        }
        if (pool.isEmpty()) {
            return false;
        }
        Collections.shuffle(pool, random);

        // 补进来固定 1 级
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(existing);
        mutable.set(pool.get(0), 1);
        stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        return true;
    }

    /**
     * 随机挑一条可升级的附魔升一级。
     *
     * @return 是否有附魔被升级
     */
    private boolean tryUpgrade(ItemStack stack) {
        ItemEnchantments existing = stack.getEnchantments();
        List<Holder<Enchantment>> upgradable = new ArrayList<>();
        for (Holder<Enchantment> holder : existing.keySet()) {
            if (canUpgrade(stack, holder)) {
                upgradable.add(holder);
            }
        }
        if (upgradable.isEmpty()) {
            return false;
        }
        Collections.shuffle(upgradable, random);
        Holder<Enchantment> target = upgradable.get(0);
        int newLevel = existing.getLevel(target) + 1;

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(existing);
        mutable.set(target, newLevel);
        stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        return true;
    }

    /** 从背包里扣掉若干个凝造青金。 */
    private boolean consumeLapis(int amount) {
        int remaining = amount;
        var inv = menuPlayer.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!(s.getItem() instanceof CondensedLapisItem)) {
                continue;
            }
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private void sync() {
        if (owner != null) {
            com.example.smalltools.net.EnhancerSyncPayload.send(owner, this);
        }
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide()) {
            clearContainer(player, container);
        }
        super.removed(player);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
