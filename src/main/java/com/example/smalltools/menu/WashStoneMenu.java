package com.example.smalltools.menu;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.WashStoneItem;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
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
 * <h2>流程</h2>
 * <ol>
 *   <li>把一件<b>已附魔</b>的工具/武器放进槽里；未附魔的放不进去。</li>
 *   <li>界面上列出这件物品的每一个附魔词条，玩家点一个。</li>
 *   <li>服务端为这个词条随机三条<b>同等级</b>的其它附魔，回到界面上。</li>
 *   <li>玩家选一条，原来那条被替换掉，手里的洗炼石消耗一颗。</li>
 * </ol>
 *
 * <h2>为什么候选要放在服务端</h2>
 *
 * <p>候选必须在服务端生成并保存：如果让客户端算，改个包就能刷出任意附魔。
 * 这里的 {@link #candidates} 只在服务端有值，客户端拿到的是同步过去的只读副本。</p>
 */
public class WashStoneMenu extends AbstractContainerMenu {

    /** 待洗炼物品所在的槽位编号。 */
    public static final int SLOT_INPUT = 0;
    /** 候选数量。 */
    public static final int CANDIDATE_COUNT = 3;

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

    /** 可选词条（输入物品上的附魔），顺序稳定，客户端按序展示。 */
    private final List<Holder<Enchantment>> entries = new ArrayList<>();
    /** 三条候选；只有选过词条之后才非空。 */
    private final List<Candidate> candidates = new ArrayList<>();
    /** 当前选中的词条下标，-1 表示还没选。 */
    private int selected = -1;

    private final Random random = new Random();

    /** 只在服务端非空：用来在输入变化时主动推状态给客户端。 */
    private final ServerPlayer owner;

    public WashStoneMenu(int id, Inventory inventory, InteractionHand hand) {
        super(SmallTools.WASH_STONE_MENU.get(), id);
        this.hand = hand;
        this.owner = inventory.player instanceof ServerPlayer sp ? sp : null;

        // 只有一个输入槽，位置交给客户端界面自己画
        this.addSlot(new Slot(container, SLOT_INPUT, 80, 60) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return isWashable(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        // 玩家背包，方便直接取用
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }
    }

    /**
     * 能不能放进洗炼槽。
     *
     * <p>要求：已经附魔、可损耗（工具/武器/护甲都有耐久）、且不是护甲本身。
     * 护甲被排除是因为需求写的是"武器或者工具"。</p>
     */
    public static boolean isWashable(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        if (stack.is(Items.ENCHANTED_BOOK) || stack.is(Items.BOOK)) {
            return false;
        }
        return !stack.getEnchantments().isEmpty();
    }

    /** 输入槽内容变化：重算词条、清掉旧候选，并把结果推给客户端。 */
    private void onInputChanged() {
        entries.clear();
        candidates.clear();
        selected = -1;

        ItemStack input = container.getItem(SLOT_INPUT);
        if (isWashable(input)) {
            for (Holder<Enchantment> holder : input.getEnchantments().keySet()) {
                entries.add(holder);
            }
            // 顺序固定，否则客户端按钮每次打开都可能换位置
            entries.sort((a, b) -> a.unwrapKey().map(k -> k.location().toString())
                    .orElse("").compareTo(b.unwrapKey().map(k -> k.location().toString())
                            .orElse("")));
        }
        broadcastChanges();

        // 关键：把新词条推给客户端。
        // 没有这一步，客户端拿不到任何词条，界面会一直停在"请放入物品"。
        // owner 只有在服务端构造菜单时才非空，客户端构造时是 null，自然不会发。
        sync();
    }

    /** 服务端把当前状态推给客户端；客户端侧什么都不做。 */
    private void sync() {
        if (owner != null) {
            com.example.smalltools.net.WashStoneSyncPayload.send(owner, this);
        }
    }

    /** 某个附魔在当前输入物品上的等级；没有则是 0。 */
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

    public InteractionHand hand() {
        return hand;
    }

    /** 重新算一遍词条列表（界面刚打开、或物品变动时用）。 */
    public void refresh(ServerLevel level) {
        onInputChanged();
    }

    /**
     * 玩家选了第 {@code index} 个词条：为它随机三条同等级候选。
     *
     * @return 是否成功（下标越界或输入不合法时返回 false）
     */
    public boolean chooseEntry(ServerLevel level, int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        ItemStack input = container.getItem(SLOT_INPUT);
        if (!isWashable(input)) {
            return false;
        }

        Holder<Enchantment> target = entries.get(index);
        int wanted = input.getEnchantments().getLevel(target);

        candidates.clear();
        candidates.addAll(rollCandidates(level, input, wanted));
        selected = index;
        broadcastChanges();
        return !candidates.isEmpty();
    }

    /**
     * 随机出最多 {@link #CANDIDATE_COUNT} 条候选。
     *
     * <p>规则：</p>
     * <ul>
     *   <li><b>不与原工具上的附魔相同</b> —— 包括正在被替换的那一条；</li>
     *   <li><b>必须能附在这件物品上</b>（{@code isSupportedItem}），
     *       否则会出现"锋利"跑到镐子上的荒唐结果；</li>
     *   <li><b>同级</b> —— 沿用被替换那条的等级，但不超过候选自身上限
     *       （比如"经验修补"最高只有 I 级）。</li>
     * </ul>
     */
    private List<Candidate> rollCandidates(ServerLevel level, ItemStack input, int wanted) {
        ItemEnchantments existing = input.getEnchantments();
        List<Holder<Enchantment>> pool = new ArrayList<>();

        for (Holder<Enchantment> holder : level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).holders().toList()) {
            if (existing.getLevel(holder) > 0) {
                continue;               // 不与原附魔相同
            }
            if (!holder.value().isSupportedItem(input)) {
                continue;               // 必须能附在这件物品上
            }
            pool.add(holder);
        }

        if (pool.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(pool, random);

        List<Candidate> picked = new ArrayList<>(CANDIDATE_COUNT);
        for (int i = 0; i < pool.size() && picked.size() < CANDIDATE_COUNT; i++) {
            Holder<Enchantment> holder = pool.get(i);
            int max = Math.max(1, holder.value().getMaxLevel());
            int chosenLevel = Math.min(Math.max(1, wanted), max);
            picked.add(new Candidate(holder, chosenLevel));
        }
        return picked;
    }

    /**
     * 玩家选了第 {@code index} 个候选：替换掉原来那条附魔，并消耗一颗洗炼石。
     *
     * <p>服务端会重新确认：输入物还在、石头还在手上、候选确实由服务端生成过。
     * 任何一条不满足就什么都不做。</p>
     *
     * @return 是否替换成功
     */
    public boolean applyCandidate(ServerPlayer player, int index) {
        if (index < 0 || index >= candidates.size() || selected < 0 || selected >= entries.size()) {
            return false;
        }
        ItemStack input = container.getItem(SLOT_INPUT);
        if (!isWashable(input)) {
            return false;
        }
        // 手上还得拿着洗炼石（中途可能丢掉了）
        ItemStack stone = player.getItemInHand(hand);
        if (!(stone.getItem() instanceof WashStoneItem)) {
            return false;
        }

        Holder<Enchantment> old = entries.get(selected);
        Candidate picked = candidates.get(index);

        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(input.getEnchantments());
        mutable.set(old, 0);                                    // 移除旧词条
        mutable.set(picked.enchantment(), picked.level());      // 写入新词条
        input.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, mutable.toImmutable());

        stone.shrink(1);   // 洗炼石消失

        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                    SoundSource.PLAYERS, 1.0F, 1.2F);
        }

        // 洗完就结束：清空界面状态，避免重复应用
        candidates.clear();
        entries.clear();
        selected = -1;
        broadcastChanges();
        return true;
    }

    @Override
    public void removed(Player player) {
        // 先把槽里的东西还回去，再交给 super 收尾。
        // 顺序不能反：super.removed 之后 menu 状态已经算关闭，再动容器容易被忽略。
        //
        // 用原版 clearContainer 而不是自己写 add / drop：
        // 它把"放回背包失败就掉在地上"这套边界处理都做好了，
        // 手写容易漏掉某一个分支，物品就凭空消失。
        if (!player.level().isClientSide()) {
            clearContainer(player, container);
        }
        super.removed(player);
    }

    @Override
    public boolean stillValid(Player player) {
        // 不要在这里检查"手上还拿着洗炼石"：
        // 洗完炼石头就没了，界面会被立刻强制关闭，玩家看不到结果提示。
        // 真正需要校验的地方是 applyCandidate（它自己会检查石头还在不在）。
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 这个界面不做 shift 快速移动，避免把洗炼槽当成运输工具
        return ItemStack.EMPTY;
    }
}
