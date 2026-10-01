package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.ModList;

/**
 * 星辉（Astral Sorcery）适配：把它的暴击 perk 并入本 mod 的暴击区。
 *
 * <h2>为什么需要单独适配</h2>
 * 星辉的暴击<b>不是</b> Minecraft 的 {@code Attribute}，而是它自家的
 * {@code PerkAttributeType}，注册在 {@code RegistriesAS.REGISTRY_PERK_ATTRIBUTE_TYPES}：
 * <ul>
 *   <li>{@code astralsorcery:critical_hit_chance} —— 暴击概率（0.05 = 5%）</li>
 *   <li>{@code astralsorcery:critical_hit_damage} —— 暴击伤害加成（0.1 = +10%）</li>
 * </ul>
 * 因此原版的属性系统完全看不到它们，必须主动去读。
 *
 * <h2>为什么要接管判定权</h2>
 * 星辉自己在 {@code CriticalHitEvent} 上掷骰（{@code EventPriority.HIGH}）并设置
 * {@code setCriticalHit(true)}，而本 mod 也在同一事件上接管暴击
 * （{@code EventPriority.HIGHEST}，会把结果压回 {@code false}）。
 * 两者并存会产生<b>两个骰子</b>，且星辉那一次还会被本 mod 的乘区重建覆盖掉——
 * 结果是它的 perk「能触发但打不出伤害」。
 *
 * <p>所以这里采取「<b>数值并入、判定归一</b>」：把星辉的概率与暴伤读出来并入本 mod 的
 * {@code crit_chance} / {@code crit_damage_bonus}，<b>骰子仍然只有一个</b>，由本 mod 掷。
 *
 * <h2>为什么把互操作代码关在内部类里</h2>
 * 星辉是<b>可选</b>依赖。只要类加载器去解析星辉的类型，没装的整合包就会抛
 * {@code NoClassDefFoundError}。因此分两层：
 * <ul>
 *   <li>外层 {@link AstralCompat} 只用 {@code ModList} 做字符串判断，不引用任何星辉类型；</li>
 *   <li>内层 {@link Hook} 才真正引用星辉类型，且<b>只在确认装了星辉之后</b>才会被 JVM 加载。</li>
 * </ul>
 * 编译期用 {@code compileOnly} 依赖，因此这里写的是正常类型化代码。
 */
public final class AstralCompat {

    /** 星辉的 mod id。 */
    private static final String ASTRAL_MODID = "astralsorcery";

    private static boolean probed;
    private static boolean available;
    private static boolean readErrorLogged;

    private AstralCompat() {
    }

    /**
     * 探测星辉并准备好适配（幂等）。
     *
     * <p>必须在<b>全部 mod 构造与注册完成之后</b>调用（本 mod 走的是
     * {@code FMLCommonSetupEvent}）：mod 构造是并行的，若在这里之前就去取星辉的
     * {@code DeferredHolder}，可能早于星辉建好自己的注册表而误判成「不可用」。
     *
     * <p>没装星辉时只打印一条信息，不做任何事。
     */
    public static synchronized void ensureRegistered() {
        if (probed) {
            return;
        }
        probed = true;

        // 用 ModList 判断，不触发任何星辉类的加载。
        if (!ModList.get().isLoaded(ASTRAL_MODID)) {
            DamageModernization.LOGGER.info(
                    "未检测到 Astral Sorcery，暴击只使用本 mod 的 crit_chance / crit_damage_bonus");
            return;
        }

        try {
            // 触碰一次 Hook，确认它的依赖（星辉的注册表）确实可用。
            Hook.probe();
            available = true;
            DamageModernization.LOGGER.info(
                    "检测到 Astral Sorcery，已把它的暴击概率/暴击伤害并入本 mod 的暴击区");
        } catch (Throwable t) {
            available = false;
            DamageModernization.LOGGER.error(
                    "Astral Sorcery 适配初始化失败，将忽略它的暴击 perk", t);
        }
    }

    /**
     * {@return 星辉为这名玩家提供的额外暴击概率；没有则返回 0}
     *
     * <p>量纲与本 mod 的 {@code crit_chance} 一致（0.1 = +10%），可直接相加。
     *
     * @param player 攻击者
     */
    public static double extraCritChance(Player player) {
        if (!available || player == null) {
            return 0.0D;
        }
        try {
            return Hook.extraCritChance(player);
        } catch (Throwable t) {
            reportReadError("读取星辉暴击概率失败", t);
            return 0.0D;
        }
    }

    /**
     * {@return 星辉为这名玩家提供的额外暴击伤害加成；没有则返回 0}
     *
     * <p>量纲与本 mod 的 {@code crit_damage_bonus} 一致（0.1 = +10%），可直接相加。
     *
     * @param player 攻击者
     */
    public static double extraCritDamageBonus(Player player) {
        if (!available || player == null) {
            return 0.0D;
        }
        try {
            return Hook.extraCritDamageBonus(player);
        } catch (Throwable t) {
            reportReadError("读取星辉暴击伤害失败", t);
            return 0.0D;
        }
    }

    /** {@return 星辉适配是否已启用} */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * 运行期读取出错时记录一次，并<b>按 0 处理</b>。
     *
     * <p>刻意<b>不</b>把适配标记为不可用：这些方法会被 GUI 每若干帧调用一次，
     * 若因为一次客户端抖动就永久关掉，整局游戏都会丢掉星辉加成——代价远大于收益。
     * 用一次性标志避免刷屏即可。
     *
     * @param message 说明
     * @param cause   异常
     */
    private static void reportReadError(String message, Throwable cause) {
        if (readErrorLogged) {
            return;
        }
        readErrorLogged = true;
        DamageModernization.LOGGER.error(
                "{}，本次按 0 处理（后续同类错误不再重复记录）", message, cause);
    }

    /**
     * 真正引用星辉类型的部分。
     *
     * <p>这个类<b>只能</b>在 {@link #ensureRegistered()} 确认星辉存在之后再被触碰，
     * 否则会引发类加载错误。
     */
    private static final class Hook {

        private Hook() {
        }

        /**
         * 触碰一次星辉的注册表，用于确认适配可用。
         *
         * <p>注册表此时应当已加载完毕；取不到就说明版本不匹配，直接抛出让外层停用。
         */
        static void probe() {
            hellfirepvp.astralsorcery.common.lib.PerksAS.AttributeTypes.CRITICAL_HIT_CHANCE.get();
            hellfirepvp.astralsorcery.common.lib.PerksAS.AttributeTypes.CRITICAL_HIT_DAMAGE.get();
        }

        /**
         * 读取星辉的暴击概率。
         *
         * <p>与星辉自己的算法保持一致：用 {@code modifyValue} 聚合（而不是自己累加 perk
         * 数据文件里的 {@code value}），再过一遍它的后处理事件，否则别家对星辉数值的
         * 修改会被丢掉。
         *
         * @param player 攻击者
         * @return 暴击概率（0.05 = 5%）
         */
        static double extraCritChance(Player player) {
            return read(player,
                    hellfirepvp.astralsorcery.common.lib.PerksAS.AttributeTypes.CRITICAL_HIT_CHANCE.get(),
                    true);
        }

        /**
         * 读取星辉的暴击伤害加成。
         *
         * @param player 攻击者
         * @return 暴击伤害加成（0.1 = +10%）
         */
        static double extraCritDamageBonus(Player player) {
            return read(player,
                    hellfirepvp.astralsorcery.common.lib.PerksAS.AttributeTypes.CRITICAL_HIT_DAMAGE.get(),
                    false);
        }

        /**
         * 按星辉自己的方式取出一个 perk 属性的最终值。
         *
         * @param player     攻击者
         * @param type       目标 perk 属性
         * @param accumulate 是否使用「聚合」语义（概率用它，伤害加成用 {@code getModifier}）
         * @return 数值；玩家没有该 perk 时为 0
         */
        private static double read(Player player,
                                   hellfirepvp.astralsorcery.common.perk.type.base.PerkAttributeType type,
                                   boolean accumulate) {
            LogicalSide side = hellfirepvp.astralsorcery.common.util.SidedHelper.getSide(player);
            if (side == null) {
                return 0.0D;
            }

            hellfirepvp.astralsorcery.common.research.PlayerProgress progress =
                    hellfirepvp.astralsorcery.common.research.ResearchManager.getProgress(player, side);
            // 进度无效表示这名玩家还没接触星辉，直接当没有。
            if (progress == null || !progress.isValid()) {
                return 0.0D;
            }

            hellfirepvp.astralsorcery.common.perk.PerkAttributeMap map =
                    hellfirepvp.astralsorcery.common.perk.PerkManager.getOrCreateAttributes(player);
            if (map == null) {
                return 0.0D;
            }

            float value = accumulate
                    ? map.modifyValue(player, progress, type, 0.0F)
                    : map.getModifier(player, progress, type);

            // 与星辉自己一致：再过一遍它的后处理事件。
            value = hellfirepvp.astralsorcery.common.event.AttributeEvent
                    .postProcessModded(player, type, value);

            return Double.isFinite(value) ? value : 0.0D;
        }
    }
}
