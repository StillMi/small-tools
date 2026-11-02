package com.example.smalltools.menu;

import com.example.smalltools.SmallTools;
import com.example.smalltools.item.WashStoneItem;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
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
 * <h2>流程与不可逆点</h2>
 * <ol>
 *   <li>把一件<b>已附魔</b>的工具/武器放进槽里；未附魔的放不进去。</li>
 *   <li>界面列出这件物品的每个附魔词条。</li>
 *   <li><b>点下一个词条之后就不能反悔了</b>：洗炼石当场消耗，
 *       输入槽锁死，服务端立刻随机三条候选。此后关界面也不会退还石头。</li>
 *   <li>选一条候选，替换掉原来那条附魔。</li>
 * </ol>
 *
 * <h2>为什么石头在"选词条"时就扣</h2>
 *
 * <p>需求是"一旦显示要替换的附魔就不可取消，石头一定会被消耗"。如果把扣除放在
 * 最后一步（选候选），玩家看到三条候选都不满意时直接 Esc 就能白嫖一次随机结果、
 * 石头还在手里。所以锁定和扣石头必须发生在同一个动作里。</p>
 *
 * <h2>为什么候选要放在服务端</h2>
 *
 * <p>候选必须由服务端生成并保存：如果让客户端算，改个包就能刷出任意附魔。</p>
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
    /** 三条候选；只有锁定之后才非空。 */
    private final List<Candidate> candidates = new ArrayList<>();
    /** 当前选中的词条下标，-1 表示还没选。 */
    private int selected = -1;
    /**
     * 是否已经锁定。
     *
     * <p>锁定后：输入槽不可再动、石头已扣、候选已生成。关界面也不回退。</p>
     */
    private boolean locked;

    private final Random random = new Random();

    /** 只在服务端非空：用来把状态推给客户端。 */
    private final ServerPlayer owner;
    /** 洗炼石是在哪只手用的（扣石头时要用）。 */
    private final Player menuPlayer;

    public WashStoneMenu(int id, Inventory inventory, InteractionHand hand) {
        super(SmallTools.WASH_STONE_MENU.get(), id);
        this.hand = hand;
        this.menuPlayer = inventory.player;
        this.owner = inventory.player instanceof ServerPlayer sp ? sp : null;

        // 只有一个输入槽，位置交给客户端界面自己画
        this.addSlot(new Slot(container, SLOT_INPUT, 80, 60) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // 锁定后不许再塞东西进来
                return !WashStoneMenu.this.locked && isWashable(stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                // 锁定后不许把物品拿走：候选是按这件物品算出来的，
                // 换了物品就对不上了
                return !WashStoneMenu.this.locked;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        // 玩家背包
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
     * <p>要求：已附魔、有耐久（工具/武器），且不是书。护甲也算有耐久，
     * 但需求写的是"武器或者工具"，所以额外排除护甲。</p>
     */
    public static boolean isWashable(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        if (stack.is(Items.ENCHANTED_BOOK) || stack.is(Items.BOOK)) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.ArmorItem) {
            return false;
        }
        return !stack.getEnchantments().isEmpty();
    }

    /** 输入槽内容变化：重算词条，清掉候选与锁定。 */
    private void onInputChanged() {
        entries.clear();
        candidates.clear();
        selected = -1;
        // 只有还没锁定时才允许重算；锁定后槽位本来也动不了，
        // 这里多一层保险，避免任何路径把候选清掉却已经扣了石头
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
        broadcastChanges();
        sync();
    }

    /**
     * 附魔的显示名。
     *
     * <p>用 {@code Enchantment.getFullname} —— 它读的是 {@code Enchantment#description}
     * 这个 Component 字段，并自动补上罗马数字等级，和原版附魔书完全一致。
     * <b>不要</b>自己拼 {@code enchantment.<命名空间>.<路径>}：那只是 id 的巧合映射，
     * 模组附魔或自定义 description 会解析失败，界面上就出现一整串英文标识。</p>
     */
    public Component displayNameOf(Holder<Enchantment> holder, int level) {
        return Enchantment.getFullname(holder, level);
    }

    /** 词条列表里的显示名（等级取自当前物品）。 */
    public Component displayNameOf(Holder<Enchantment> holder) {
        return displayNameOf(holder, Math.max(1, levelOf(holder)));
    }

    /** 排序用的名字，避免每次打开界面按钮顺序乱跳。 */
    private Component nameOf(Holder<Enchantment> holder) {
        return displayNameOf(holder);
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

    public boolean locked() {
        return locked;
    }

    public InteractionHand hand() {
        return hand;
    }

    /** 重新算一遍词条列表（界面刚打开时用）。 */
    public void refresh(ServerLevel level) {
        onInputChanged();
    }

    /**
     * 玩家选了第 {@code index} 个词条。
     *
     * <p><b>这一步不可逆</b>：立刻扣掉洗炼石、锁死槽位、生成候选。</p>
     *
     * @return 是否成功
     */
    public boolean chooseEntry(ServerLevel level, int index) {
        if (locked) {
            return false;                       // 已经锁了，重复点击无效
        }
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        ItemStack input = container.getItem(SLOT_INPUT);
        if (!isWashable(input)) {
            return false;
        }
        // 手上得真的有洗炼石
        ItemStack stone = menuPlayer.getItemInHand(hand);
        if (!(stone.getItem() instanceof WashStoneItem)) {
            return false;
        }

        Holder<Enchantment> target = entries.get(index);
        int wanted = Math.max(1, input.getEnchantments().getLevel(target));

        candidates.clear();
        candidates.addAll(rollCandidates(level, input, wanted));
        if (candidates.isEmpty()) {
            // 没有可换的附魔：不锁定、不扣石头，避免白吃一颗
            return false;
        }

        // 锁定 + 扣石头：两者必须同时发生
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
     * 随机出最多 {@link #CANDIDATE_COUNT} 条候选。
     *
     * <p>规则：不与物品上已有的附魔重复（含正在被替换那条）、必须能附在这件物品上、
     * 等级沿用被替换那条但不超过候选自身上限。</p>
     */
    private List<Candidate> rollCandidates(ServerLevel level, ItemStack input, int wanted) {
        ItemEnchantments existing = input.getEnchantments();
        List<Holder<Enchantment>> pool = new ArrayList<>();

        for (Holder<Enchantment> holder : level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).holders().toList()) {
            if (existing.getLevel(holder) > 0) {
                continue;
            }
            if (!input.supportsEnchantment(holder)) {
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
            Holder<Enchantment> holder = pool.get(i);
            int max = Math.max(1, holder.value().getMaxLevel());
            picked.add(new Candidate(holder, Math.min(wanted, max)));
        }
        return picked;
    }

    /**
     * 玩家选了第 {@code index} 个候选：替换附魔。
     *
     * <p>洗炼石已经在 {@link #chooseEntry} 里扣过了，这里<b>不再扣</b>。
     * 仍然要重新校验输入物品和候选，防止下标越界或状态被改。</p>
     *
     * @return 是否替换成功
     */
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

        // 洗完后解锁，物品可以取走了；候选清空避免重复应用
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
        // 先归还槽里的物品，再让父类收尾。
        // 锁定期间 mayPickup 为 false，但关界面时仍然要还给玩家，不能吞掉装备。
        if (!player.level().isClientSide()) {
            clearContainer(player, container);
        }
        super.removed(player);
    }

    /**
     * 不检查"手上还拿着洗炼石"。
     *
     * <p>洗完或锁定后石头就没了，若在这里校验，界面会被原版立刻强制关闭，
     * 玩家看不到结果。真正的校验在 {@link #chooseEntry} 里。</p>
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
