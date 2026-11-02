package com.example.smalltools.menu;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.WashStoneItem;
import com.example.smalltools.portal.WashLuck;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * 附魔洗炼界面。
 *
 * <h2>流程与不可逆点</h2>
 * <ol>
 *   <li>把一件<b>已附魔</b>的工具/武器放进槽里；未附魔的放不进去。</li>
 *   <li>界面列出这件物品的每个附魔词条。</li>
 *   <li><b>点下一个词条之后就不可撤销</b>：洗炼石当场消耗、输入槽锁死、
 *       服务端立刻随机三条候选。此后关界面也不退还石头。</li>
 *   <li>选一条候选，替换掉原来那条附魔。</li>
 * </ol>
 *
 * <h2>等级规则（±1）</h2>
 *
 * <p>候选的等级在原附魔等级 <b>±1</b> 范围内随机，并夹在
 * {@code [1, 该附魔最大等级]} 之间。也就是说原来 II 级，可能出 I / II / III。</p>
 *
 * <h2>运气条</h2>
 *
 * <p>玩家每次洗炼累积 30%~45% 的运气，攒满 100% 后<b>下一次</b>洗炼的三条候选
 * 全部取该附魔的最高等级，然后清零。运气是玩家属性，跨物品跨门累计，
 * 存在 {@link WashLuck}。</p>
 */
public class WashStoneMenu extends AbstractContainerMenu {

    /** 待洗炼物品所在的槽位编号。 */
    public static final int SLOT_INPUT = 0;
    /** 候选数量。 */
    public static final int CANDIDATE_COUNT = 3;

    /**
     * 面板尺寸与槽位坐标。
     *
     * <p>这些必须和 {@code WashStoneScreen} 里的布局常量完全一致：
     * {@code Slot.x / Slot.y} 在 1.21.1 里是 <b>final</b>，界面没法在 init 里改，
     * 只能在构造菜单时就把坐标算对。</p>
     */
    public static final int PANEL_W = 200;
    public static final int PANEL_H = 226;
    /** 输入槽左上角（面板内坐标）。 */
    public static final int SLOT_U = (PANEL_W - 18) / 2;
    public static final int SLOT_V = 58;
    /** 物品栏那一行（面板内坐标）。 */
    public static final int INV_U = (PANEL_W - 9 * 18) / 2;
    public static final int INV_V = 186;

    /** 一条候选附魔。 */
    public record Candidate(Holder<Enchantment> enchantment, int level) {
    }

    private final InteractionHand hand;
    private final SimpleContainer container = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            WashStoneMenu.this.onInputChanged();
        }
    };

    private final List<Holder<Enchantment>> entries = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private int selected = -1;
    private boolean locked;

    /** 服务端算出的运气百分比（0~100），只用于显示。 */
    private int luckPercent;

    private final Random random = new Random();
    private final ServerPlayer owner;
    private final Player menuPlayer;

    public WashStoneMenu(int id, Inventory inventory, InteractionHand hand) {
        super(SmallTools.WASH_STONE_MENU.get(), id);
        this.hand = hand;
        this.menuPlayer = inventory.player;
        this.owner = inventory.player instanceof ServerPlayer sp ? sp : null;

        // 输入槽：坐标直接按面板内的位置算好
        this.addSlot(new Slot(container, SLOT_INPUT, SLOT_U + 1, SLOT_V + 1) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !WashStoneMenu.this.locked && isWashable(stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                return !WashStoneMenu.this.locked;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        // 只放物品栏那一行（9 格）。
        // 需求是"不显示背包"，所以主背包那 27 格不加进菜单。
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, INV_U + 1 + col * 18, INV_V + 1));
        }

        refreshLuck();
    }

    /** 读一次运气，供界面显示。 */
    private void refreshLuck() {
        if (owner != null) {
            luckPercent = WashLuck.get(owner.server).get(owner) / 100;
        }
    }

    /**
     * 能不能放进洗炼槽。
     *
     * <p>规则只有一条：<b>有附魔就行</b>。工具、武器、护甲、三叉戟、弓、
     * 甚至模组加的装备都算 —— 只要有附魔词条，就能拿来洗。</p>
     *
     * <p>不要求"可损耗"：附魔书虽然有附魔，但它是一个容器而不是被附魔的实体装备，
     * 洗它的词条没有意义（而且会破坏书本身），所以排除；普通书不带附魔，
     * 自然也不会通过。</p>
     */
    public static boolean isWashable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // 附魔书是"装着附魔的容器"，不是被附魔的物品，排除掉
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return false;
        }
        // 唯一硬条件：身上真的有附魔
        return !stack.getEnchantments().isEmpty();
    }

    private void onInputChanged() {
        entries.clear();
        candidates.clear();
        selected = -1;
        if (locked) {
            sync();
            return;
        }

        ItemStack input = container.getItem(SLOT_INPUT);
        if (isWashable(input)) {
            for (Holder<Enchantment> holder : input.getEnchantments().keySet()) {
                entries.add(holder);
            }
            entries.sort((a, b) -> nameOf(a).getString().compareTo(nameOf(b).getString()));
        }
        refreshLuck();
        broadcastChanges();
        sync();
    }

    /**
     * 附魔显示名，等级用<b>传入的</b> level。
     *
     * <p>这里必须显式传 level：候选是"物品上还没有的附魔"，
     * 若拿 {@code levelOf(holder)} 去取，永远是 0，界面就会显示成没有等级，
     * 而实际写入用的是候选自己的等级 —— 显示与实际不一致。</p>
     */
    public Component displayNameOf(Holder<Enchantment> holder, int level) {
        return Enchantment.getFullname(holder, Math.max(1, level));
    }

    /** 词条列表用：等级就是物品上那条的实际等级。 */
    public Component displayNameOf(Holder<Enchantment> holder) {
        return displayNameOf(holder, Math.max(1, levelOf(holder)));
    }

    private Component nameOf(Holder<Enchantment> holder) {
        return displayNameOf(holder);
    }

    public int levelOf(Holder<Enchantment> holder) {
        return container.getItem(SLOT_INPUT).getEnchantments().getLevel(holder);
    }

    public List<Holder<Enchantment>> entries() {
        return entries;
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public int selected() {
        return selected;
    }

    public boolean locked() {
        return locked;
    }

    public int luckPercent() {
        return luckPercent;
    }

    public InteractionHand hand() {
        return hand;
    }

    public void refresh(ServerLevel level) {
        onInputChanged();
    }

    /**
     * 玩家选了第 {@code index} 个词条：立刻扣石头、锁定、生成候选。
     *
     * @return 是否成功
     */
    public boolean chooseEntry(ServerLevel level, int index) {
        if (locked) {
            return false;
        }
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        ItemStack input = container.getItem(SLOT_INPUT);
        if (!isWashable(input)) {
            return false;
        }
        ItemStack stone = menuPlayer.getItemInHand(hand);
        if (!(stone.getItem() instanceof WashStoneItem)) {
            return false;
        }

        Holder<Enchantment> target = entries.get(index);
        int baseLevel = Math.max(1, input.getEnchantments().getLevel(target));

        // 运气：满了就这次全取最高等级
        WashLuck luckData = owner != null ? WashLuck.get(owner.server) : null;
        // 只看"待生效标记"，不看进度数值：
        // 数值可能因为已攒满而停在 100%，但那一次用完就清零，不能拿它判断
        boolean useFull = luckData != null && owner != null && luckData.isArmed(owner);

        candidates.clear();
        candidates.addAll(rollCandidates(level, input, baseLevel, useFull));
        if (candidates.isEmpty()) {
            return false;   // 没有可换的：不锁定、不扣石头
        }

        // 先推进运气，再扣石头。
        // 顺序反过来的话，万一 advance 抛异常，石头已经没了但运气没记上。
        if (luckData != null && owner != null) {
            luckData.advance(owner, useFull, random);
            refreshLuck();
        }

        // 锁定 + 扣石头
        stone.shrink(1);
        this.locked = true;
        this.selected = index;

        level.playSound(null, menuPlayer.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.PLAYERS, 0.8F, 1.4F);

        broadcastChanges();
        sync();
        return true;
    }

    /**
     * 随机三条候选。
     *
     * @param baseLevel 原附魔等级，候选在 ±1 内浮动
     * @param maxRoll   true 表示运气满了：全部取最高等级
     */
    private List<Candidate> rollCandidates(ServerLevel level, ItemStack input,
                                           int baseLevel, boolean maxRoll) {
        ItemEnchantments existing = input.getEnchantments();
        List<Holder<Enchantment>> pool = new ArrayList<>();

        for (Holder<Enchantment> holder : level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).holders().toList()) {
            if (existing.getLevel(holder) > 0) {
                continue;                       // 不与已有附魔重复
            }
            if (!input.supportsEnchantment(holder)) {
                continue;                       // 必须能附在这件物品上
            }
            // 过滤负面附魔：绑定诅咒、消失诅咒都挂在这个标签下。
            // 用原版标签而不是硬编码 id，这样模组加进来的诅咒也能一并滤掉。
            if (holder.is(EnchantmentTags.CURSE)) {
                continue;
            }
            pool.add(holder);
        }

        if (pool.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(pool, random);

        List<Candidate> picked = new ArrayList<>(CANDIDATE_COUNT);
        for (int i = 0; i < pool.size() && picked.size() < CANDIDATE_COUNT; i++) {
            picked.add(new Candidate(pool.get(i), pickLevel(pool.get(i), baseLevel, maxRoll)));
        }
        return picked;
    }

    /**
     * 按 ±1 规则取一个合法等级。
     *
     * <p>基准是原附魔等级，先在 {@code base-1 .. base+1} 里随机，再夹到
     * {@code [1, 该附魔最大等级]}。运气满时直接取最大值。</p>
     */
    private int pickLevel(Holder<Enchantment> holder, int baseLevel, boolean maxRoll) {
        int max = Math.max(1, holder.value().getMaxLevel());
        if (maxRoll) {
            return max;
        }
        int offset = random.nextInt(3) - 1;     // -1 / 0 / +1
        int target = baseLevel + offset;
        return Math.max(1, Math.min(target, max));
    }

    /** 选了候选：替换附魔。洗炼石已在 chooseEntry 扣过。 */
    public boolean applyCandidate(ServerPlayer player, int index) {
        if (!locked || selected < 0 || selected >= entries.size()) {
            return false;
        }
        if (index < 0 || index >= candidates.size()) {
            return false;
        }
        ItemStack input = container.getItem(SLOT_INPUT);
        if (input.isEmpty()) {
            return false;
        }

        Holder<Enchantment> old = entries.get(selected);
        Candidate picked = candidates.get(index);

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(input.getEnchantments());
        mutable.set(old, 0);
        mutable.set(picked.enchantment(), picked.level());
        input.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());

        candidates.clear();
        entries.clear();
        selected = -1;
        locked = false;

        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                    SoundSource.PLAYERS, 1.0F, 0.8F);
            player.displayClientMessage(
                    Component.translatable("message.smalltools.wash.done")
                            .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE), true);
        }

        broadcastChanges();
        sync();
        return true;
    }

    private void sync() {
        if (owner != null) {
            com.example.smalltools.net.WashStoneSyncPayload.send(owner, this);
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
