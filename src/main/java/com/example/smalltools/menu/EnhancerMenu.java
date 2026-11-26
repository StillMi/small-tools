package com.example.smalltools.menu;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.LiquidLapisItem;
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
 *   <li><b>一次性投满</b>：第 n 次强化要 2 / 6 / 14 / 22 / 32 颗浓缩液态青金
 *       （折算 10 / 30 / 70 / 110 / 160 点强化经验）。点一次“投入”就把这一档
 *       需要的整份材料扣掉，{@code ENHANCE_FILL} 直接记满，不接受零散投入。</li>
 *   <li><b>补足到 3 条</b>：物品少于 3 条附魔时优先补一条随机 1 级附魔，
 *       跳过诅咒、跳过放不上去的、跳过和现有附魔互斥的
 *       （锋利/亡灵杀手、时运/精准采集、无限/修补……）。</li>
 *   <li><b>升级有上限</b>：附魔等级不超过它自身的 {@code getMaxLevel()}。
 *       随机到的那条已经满级时，本次换成强化另一条可升级的。</li>
 *   <li><b>全部满级时不能强化</b>：界面会明确提示，不扣材料；已经投进去、
 *       但再也用不上的经验会折成浓缩液态青金退回，不会卡在物品上。</li>
 * </ol>
 *
 * <h2>强化次数存在哪</h2>
 *
 * <p>次数和进度都记在<b>物品自身</b>的 {@code ENHANCE_COUNT} / {@code ENHANCE_FILL}
 * 数据组件上，因为同一件装备拿给不同的人强化，成本应该延续下去；
 * 放在玩家身上会让“换个人就便宜”成为漏洞。</p>
 */
public class EnhancerMenu extends AbstractContainerMenu {

    public static final int SLOT_ITEM = 0;

    public static final int PANEL_W = 200;
    /** 面板高度：比洗炼界面高，专门留给进度条和按钮。 */
    public static final int PANEL_H = 300;
    public static final int SLOT_U = (PANEL_W - 18) / 2;
    public static final int SLOT_V = 62;
    public static final int INV_U = (PANEL_W - 9 * 18) / 2;
    /** 物品栏放到底部，和按钮之间留足空隙，避免重叠。 */
    public static final int INV_V = 258;
    /** 强化按钮位置（面板内坐标）。 */
    public static final int BUTTON_V = 232;

    /** 补足目标：物品上最多先凑到这么多条附魔。 */
    public static final int FILL_TO = 3;

    /** 每件装备最多强化几次。 */
    public static final int MAX_ENHANCES = 5;

    /** 一颗浓缩液态青金折算多少强化经验。 */
    public static final int XP_PER_LAPIS = 5;

    /**
     * 界面只登记了快捷栏这 9 格，所以“持有多少”和“能扣多少”都只看这 9 格 ——
     * 两边必须用同一个范围，否则会出现“看不见、却算进持有并被扣掉”的情况。
     */
    public static final int LAPIS_SLOTS = 9;

    /** 刷新请求的最小间隔（tick），挡掉同 tick 反复索要状态包。 */
    private static final int REFRESH_COOLDOWN_TICKS = 2;

    /**
     * 完成第 n 次强化需要的<b>强化经验点</b>（n 从 1 开始）。
     *
     * <p>10 / 30 / 70 / 110 / 160，合计 380 点。
     * 一颗浓缩液态青金折算 {@link #XP_PER_LAPIS} 点，所以实际要投
     * 2 / 6 / 14 / 22 / 32 颗。</p>
     */
    public static int xpForStep(int step) {
        return switch (step) {
            case 1 -> 10;
            case 2 -> 30;
            case 3 -> 70;
            case 4 -> 110;
            default -> 160;
        };
    }

    /**
     * 第 n 次强化需要投入几颗浓缩液态青金。
     *
     * <p>各级经验都是 {@link #XP_PER_LAPIS} 的整数倍，这里的向上取整只是兜底，
     * 目前不会真的多收。</p>
     */
    public static int lapisForStep(int step) {
        return (xpForStep(step) + XP_PER_LAPIS - 1) / XP_PER_LAPIS;
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
    private Component lastResult = Component.empty();

    /** 上一次处理刷新请求的 tick。 */
    private long lastRefreshTick = -REFRESH_COOLDOWN_TICKS;

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
        // 强化器自身不能当被强化对象：它一旦被强化，
        // 次数就会记在“工具”上，而工具本身没有附魔，会卡在半成品状态
        if (stack.getItem() instanceof EnchantEnhancerItem) {
            return false;
        }
        return !stack.getEnchantments().isEmpty();
    }

    private void onChanged() {
        lastResult = Component.empty();
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

    /** 下一次强化需要投入几颗浓缩液态青金；已到上限时返回 0。 */
    public int costFor(ItemStack stack) {
        int count = enhanceCount(stack);
        if (count >= MAX_ENHANCES) {
            return 0;
        }
        return lapisForStep(count + 1);
    }

    /** 下一次强化需要的强化经验点总量。 */
    public int xpNeedFor(ItemStack stack) {
        int count = enhanceCount(stack);
        if (count >= MAX_ENHANCES) {
            return 0;
        }
        return xpForStep(count + 1);
    }

    /** 这件物品当前已投入的强化经验。 */
    public int fillOf(ItemStack stack) {
        return stack.getOrDefault(SmallTools.ENHANCE_FILL.get(), 0);
    }

    /** 这件装备的强化次数是否已经用满。 */
    public static boolean isExhausted(ItemStack stack) {
        int count = stack.getOrDefault(SmallTools.ENHANCE_COUNT.get(), 0);
        return count >= MAX_ENHANCES;
    }

    /** 还能强化几次。 */
    public int remaining(ItemStack stack) {
        return Math.max(0, MAX_ENHANCES - enhanceCount(stack));
    }

    /** 快捷栏 0..{@link #LAPIS_SLOTS} 里所有浓缩液态青金堆。 */
    private List<ItemStack> lapisStacks() {
        List<ItemStack> found = new ArrayList<>(LAPIS_SLOTS);
        Inventory inv = menuPlayer.getInventory();
        for (int i = 0; i < LAPIS_SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof LiquidLapisItem) {
                found.add(s);
            }
        }
        return found;
    }

    /** 玩家手上的浓缩液态青金总数（只算界面上看得见的快捷栏）。 */
    public int lapisCount() {
        int total = 0;
        for (ItemStack s : lapisStacks()) {
            total += s.getCount();
        }
        return total;
    }

    /** 这件物品上是否还有可以升级的附魔。 */
    public static boolean hasUpgradable(ItemStack stack) {
        for (Holder<Enchantment> holder : stack.getEnchantments().keySet()) {
            if (canUpgrade(stack, holder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canUpgrade(ItemStack stack, Holder<Enchantment> holder) {
        int level = stack.getEnchantments().getLevel(holder);
        return level < holder.value().getMaxLevel();
    }

    /** 是否还需要补足附魔条数。 */
    public static boolean needsFill(ItemStack stack) {
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
        if (!hasWork(stack)) {
            return false;      // 所有附魔都满级且不需要补
        }
        // 经验攒满才算“可以强化”
        return fillOf(stack) >= xpNeedFor(stack);
    }

    /**
     * 现在点“投入”会不会成功：状态能强化、这一档还没投满、材料也够。
     *
     * <p>客户端的按钮亮不亮只看这个结果，不再自己抄一份判定。</p>
     */
    public boolean canInvest(ItemStack stack) {
        if (!isEnhanceable(stack) || isExhausted(stack) || !hasWork(stack)) {
            return false;
        }
        if (fillOf(stack) >= xpNeedFor(stack)) {
            return false;      // 这一档已经投满，再吃材料就是白吃
        }
        return lapisCount() >= costFor(stack);
    }

    /** 这次强化是否还有实际可做的事（补附魔 or 升等级）。 */
    public static boolean hasWork(ItemStack stack) {
        return needsFill(stack) || hasUpgradable(stack);
    }

    public Component lastResult() {
        return lastResult;
    }

    public InteractionHand hand() {
        return hand;
    }

    /**
     * 刷新请求的节流：两个 tick 内的重复请求直接丢弃。
     *
     * <p>第一次请求一定放行（{@link #lastRefreshTick} 的初值就是负数），
     * 之后的窗口缩放之类重复触发不会放大成无限索取。</p>
     */
    public boolean shouldAcceptRefresh() {
        long now = menuPlayer.level().getGameTime();
        if (now - lastRefreshTick < REFRESH_COOLDOWN_TICKS) {
            return false;
        }
        lastRefreshTick = now;
        return true;
    }

    // ------------------------------------------------------------------
    // 强化
    // ------------------------------------------------------------------

    /**
     * 一次性投入本次强化所需的全部浓缩液态青金。
     *
     * <p>不是一颗一颗投 —— 点一下就把 {@link #costFor} 要求的数量全部扣掉，
     * 同时把经验条填满。材料不够就什么都不做，只给提示。</p>
     *
     * @return 是否投入成功
     */
    public boolean invest(ServerPlayer player) {
        ItemStack stack = container.getItem(SLOT_ITEM);

        // 附魔已经被别的途径改成“再也强化不了”时，先把卡住的进度退回去
        if (refundStuck(stack, player)) {
            broadcastChanges();
            sync();
            return false;
        }
        if (!canInvest(stack)) {
            lastResult = investFailureMessage(stack);
            sync();
            return false;
        }

        int needXp = xpNeedFor(stack);
        int needLapis = costFor(stack);

        // 扣成功后才填经验：扣不动就一颗都不动，绝不半途而废
        if (!consumeLapis(needLapis)) {
            lastResult = Component.translatable("screen.smalltools.enhancer.no_material");
            sync();
            return false;
        }
        // 这一档的经验直接记满，不留零头（各级经验本身就是 5 的整数倍）
        stack.set(SmallTools.ENHANCE_FILL.get(), needXp);

        lastResult = Component.translatable("screen.smalltools.enhancer.invested");
        broadcastChanges();
        sync();
        return true;
    }

    /**
     * 执行一次强化：要求进度已经攒满。
     *
     * @return 是否成功
     */
    public boolean enhance() {
        ItemStack stack = container.getItem(SLOT_ITEM);

        if (refundStuck(stack, owner)) {
            broadcastChanges();
            sync();
            return false;
        }
        // canEnhance 是“能不能强化”的唯一判定，这里只负责翻译失败原因
        if (!canEnhance(stack)) {
            lastResult = enhanceFailureMessage(stack);
            sync();
            return false;
        }
        if (!(menuPlayer.level() instanceof ServerLevel level)) {
            return false;
        }

        // 规划这一步做什么：先补条数，补不动再升等级
        boolean filled = false;
        if (needsFill(stack)) {
            filled = tryFill(stack, level);
        }
        if (!filled && !tryUpgrade(stack)) {
            lastResult = Component.translatable("screen.smalltools.enhancer.maxed");
            sync();
            return false;
        }

        // 强化成功：清零本次进度、记一次次数
        stack.set(SmallTools.ENHANCE_FILL.get(), 0);
        // 用 min 夹一次：万一有别的路径把次数写超了，也别让它越过 5
        int nextCount = Math.min(MAX_ENHANCES, enhanceCount(stack) + 1);
        stack.set(SmallTools.ENHANCE_COUNT.get(), nextCount);

        lastResult = Component.translatable(filled
                ? "screen.smalltools.enhancer.filled"
                : "screen.smalltools.enhancer.upgraded");

        level.playSound(null, menuPlayer.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.PLAYERS, 1.0F, 1.3F);

        broadcastChanges();
        sync();
        return true;
    }

    /**
     * 把“再也用不上”的强化进度折成浓缩液态青金退回玩家。
     *
     * <p>触发条件是次数用尽、或物品上的附魔已经被外部改到补无可补、升无可升
     * （附魔台 / 铁砧 / 砂轮），此时 {@code ENHANCE_FILL} 再没有任何出路。
     * 进度本身是 {@link #XP_PER_LAPIS} 的整数倍，直接整颗退。</p>
     *
     * @return 是否发生了退还
     */
    private boolean refundStuck(ItemStack stack, ServerPlayer player) {
        if (stack.isEmpty() || player == null) {
            return false;
        }
        if (!isExhausted(stack) && hasWork(stack)) {
            return false;              // 还用得上，别动
        }
        int fill = fillOf(stack);
        if (fill <= 0) {
            return false;
        }
        int lapis = fill / XP_PER_LAPIS;
        stack.set(SmallTools.ENHANCE_FILL.get(), 0);
        if (lapis > 0) {
            ItemStack refund = new ItemStack(SmallTools.LIQUID_LAPIS.get(), lapis);
            if (!player.getInventory().add(refund)) {
                player.drop(refund, false);   // 背包塞不下就掉在脚下
            }
        }
        lastResult = Component.translatable("screen.smalltools.enhancer.refunded", lapis);
        return true;
    }

    /** invest 失败时该说什么（调用前 {@link #canInvest} 已经为假）。 */
    private Component investFailureMessage(ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.translatable("screen.smalltools.enhancer.insert");
        }
        if (!isEnhanceable(stack)) {
            return Component.translatable("screen.smalltools.enhancer.not_enhanceable");
        }
        if (isExhausted(stack)) {
            return Component.translatable("screen.smalltools.enhancer.exhausted");
        }
        if (!hasWork(stack)) {
            return Component.translatable("screen.smalltools.enhancer.maxed");
        }
        if (fillOf(stack) >= xpNeedFor(stack)) {
            return Component.translatable("screen.smalltools.enhancer.ready");
        }
        return Component.translatable("screen.smalltools.enhancer.no_material");
    }

    /** enhance 失败时该说什么（调用前 {@link #canEnhance} 已经为假）。 */
    private Component enhanceFailureMessage(ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.translatable("screen.smalltools.enhancer.insert");
        }
        if (!isEnhanceable(stack)) {
            return Component.translatable("screen.smalltools.enhancer.not_enhanceable");
        }
        if (isExhausted(stack)) {
            return Component.translatable("screen.smalltools.enhancer.exhausted");
        }
        if (!hasWork(stack)) {
            return Component.translatable("screen.smalltools.enhancer.maxed");
        }
        return Component.translatable("screen.smalltools.enhancer.not_ready");
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
            if (holder.is(EnchantmentTags.TREASURE)) {
                continue;                       // 附魔台拿不到的（修补、冰霜行者、灵魂疾行……）
            }
            // 用 NeoForge 的 supportsEnchantment（IItemStackExtension），它比原版
            // @Deprecated 的 Enchantment#canEnchant 多考虑了附魔书与模组物品的自定义判定，
            // 也和洗炼石那边的过滤保持同一套口径
            if (!stack.supportsEnchantment(holder)) {
                continue;                       // 必须能附在这件物品上
            }
            if (conflictsWith(existing, holder)) {
                continue;                       // 不能和已有附魔互斥
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
     * 候选附魔是否和物品上已有的某条互斥。
     *
     * <p>1.21 的互斥关系写在附魔自己的 {@code exclusive_set} 里
     * （锋利 / 亡灵杀手 / 节肢杀手、时运 / 精准采集、深海探索者 / 冰霜行者……）。
     * {@link Enchantment#areCompatible} 已经双向查过，这里只负责逐个比对。</p>
     */
    private static boolean conflictsWith(ItemEnchantments existing, Holder<Enchantment> candidate) {
        for (Holder<Enchantment> other : existing.keySet()) {
            if (!Enchantment.areCompatible(other, candidate)) {
                return true;
            }
        }
        return false;
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

    /**
     * 从快捷栏扣掉若干个浓缩液态青金。
     *
     * <p>先汇总、够数再一次性扣：不够就一颗都不动，不会出现“扣了一半
     * 发现不够、已经扣掉的还回不去”的半截状态。</p>
     *
     * @return 是否扣成功
     */
    private boolean consumeLapis(int amount) {
        if (amount <= 0) {
            return true;
        }
        List<ItemStack> sources = lapisStacks();
        int available = 0;
        for (ItemStack s : sources) {
            available += s.getCount();
        }
        if (available < amount) {
            return false;
        }
        int remaining = amount;
        for (ItemStack s : sources) {
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            if (remaining <= 0) {
                break;
            }
        }
        return true;
    }

    private void sync() {
        if (owner != null) {
            com.example.smalltools.net.EnhancerSyncPayload.send(owner, this);
        }
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide()) {
            // 关界面时顺手把再也用不上的进度退掉，别让材料留在物品上
            refundStuck(container.getItem(SLOT_ITEM), owner);
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
