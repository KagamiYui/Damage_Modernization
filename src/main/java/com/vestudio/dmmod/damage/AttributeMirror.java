package com.vestudio.dmmod.damage;

import java.util.ArrayList;
import java.util.List;

import com.vestudio.dmmod.DamageModernization;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * 属性镜像工具：把一个属性的基础值与修饰符复制到另一个属性上。
 *
 * <p>本 mod 的核心手法是「<b>语义重写</b>」：原版的某个属性（攻击伤害、最大生命值）
 * 语义是「最终值」，我们把它改写成「基础值」，并在旁边维护一份副本，
 * 供乘区体系在其上叠加百分比与固定加成。
 *
 * <p>之所以抽出本类，是因为攻击力与生命值都需要同一套逻辑，
 * 差别仅在于「镜像来源」「镜像目标」与「前缀」。
 *
 * <h2>清理策略：按前缀清除，而不是靠记忆</h2>
 * 早期实现把「上一轮添加了哪些修饰符」记在一个按实体 UUID 索引的静态表里，
 * 下次据此删除。这种做法在换手、切维度、死亡重生、以及客户端/服务端
 * 各自维护一份表的情况下都可能失效，导致旧加的成残留。
 *
 * <p>现在改为：先把目标属性上所有带指定前缀的修饰符一并清除，再重建。
 * 无论上一轮处于什么状态都不会有残留——不依赖任何跨调用的记忆。
 */
public final class AttributeMirror {

    /** 基础攻击力使用的镜像前缀。 */
    public static final String ATTACK_POWER_PREFIX = "dm_attack_power_mirror_";

    /** 基础生命值使用的镜像前缀。 */
    public static final String HEALTH_PREFIX = "dm_health_mirror_";

    /** 基础护甲使用的镜像前缀。 */
    public static final String ARMOR_PREFIX = "dm_armor_mirror_";

    /** 基础盔甲韧性使用的镜像前缀。 */
    public static final String ARMOR_TOUGHNESS_PREFIX = "dm_armor_toughness_mirror_";

    /** 把「外部加成」并入提升值时使用的镜像前缀。 */
    public static final String EXTERNAL_BONUS_PREFIX = "dm_external_bonus_";

    /**
     * 被视为「外部加成」的命名空间。
     *
     * <p>这些 mod 的效果<b>不进入基础值</b>，而是并入「提升值」——
     * 基础值只应当由「原版数值 + 装备」构成，
     * 第三方 mod 的加成属于额外增益，显示上要落在括号里那一段。
     *
     * <p>目前是星辉（Astral Sorcery）：它的 perk 会以
     * {@code astralsorcery:dynamic_vanilla_modifier_<属性>_<模式>} 的名义
     * 直接挂在原版属性上（见其 {@code VanillaAttributeType}），
     * 若照单镜像就会被算成基础值。
     */
    public static final java.util.Set<String> EXTERNAL_NAMESPACES = java.util.Set.of("astralsorcery");


    private AttributeMirror() {
    }

    /**
     * 把 {@code source} 的基础值与修饰符镜像到 {@code target}。
     *
     * <p>基础值直接搬迁；修饰符以相同数值与运算方式复制。
     * 两步互不重叠，因此不会把同一份加成算两遍。
     *
     * @param target 目标属性实例
     * @param source 来源属性实例
     * @param prefix 本组镜像使用的 id 前缀（用于识别与清理）
     */
    public static void mirror(AttributeInstance target,
                             AttributeInstance source,
                             String prefix) {
        if (target == null || source == null) {
            return;
        }

        syncBaseValue(target, source);
        rebuildModifiers(target, source, prefix);
    }

    /**
     * 搬迁基础值：{@code target.baseValue ← source.baseValue}。
     *
     * @param target 目标属性
     * @param source 来源属性
     */
    public static void syncBaseValue(AttributeInstance target, AttributeInstance source) {
        double sourceBase = source.getBaseValue();
        if (!Double.isFinite(sourceBase) || sourceBase < 0.0D) {
            return;
        }
        // 仅在确有差异时写入，避免每 tick 触发属性脏标记与网络同步。
        if (Math.abs(target.getBaseValue() - sourceBase) > 1.0E-6D) {
            target.setBaseValue(sourceBase);
        }
    }

    /**
     * 重建目标属性上的镜像修饰符。
     *
     * <p>先按前缀清除旧的，再按来源当前状态重建，因此是<b>幂等</b>的。
     *
     * @param target 目标属性
     * @param source 来源属性
     * @param prefix 镜像前缀
     */
    public static void rebuildModifiers(AttributeInstance target,
                                        AttributeInstance source,
                                        String prefix) {
        removeMirrors(target, prefix);

        int index = 0;
        for (AttributeModifier modifier : source.getModifiers()) {
            // 跳过本 mod 自己写入的修饰符。
            // 既避免镜像的自我复制，也避免把「计算结果」当成「原始数据」复制过去——
            // 例如 max_health 上承载最终血量的那个修饰符，
            // 若被镜像进 base_health，就会被当成装备加成而重复计算。
            if (isOurs(modifier)) {
                continue;
            }

            // 跳过外部 mod 的加成：它们不属于基础值，由
            // {@link #mirrorExternalToBonus} 并入提升值。
            if (isExternal(modifier)) {
                continue;
            }

            ResourceLocation mirrorId = ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, prefix + index++);

            AttributeModifier mirror = new AttributeModifier(
                    mirrorId, modifier.amount(), modifier.operation());

            try {
                target.addOrUpdateTransientModifier(mirror);
            } catch (Exception e) {
                // 单个修饰符失败不应中断整体结算，仅记录调试日志。
                DamageModernization.LOGGER.debug(
                        "Failed to mirror modifier {} onto {}", modifier.id(), prefix, e);
            }
        }
    }

    /**
     * {@return 该修饰符是否来自「外部加成」命名空间}
     *
     * @param modifier 修饰符
     */
    public static boolean isExternal(AttributeModifier modifier) {
        return EXTERNAL_NAMESPACES.contains(modifier.id().getNamespace());
    }

    /**
     * 把外部模块（如星辉）挂在原版属性上的加成，改挂到「提升值」属性上。
     *
     * <h2>为什么要搬家</h2>
     * 这些加成改的是原版属性（{@code max_health} / {@code armor} …），
     * 若走常规镜像就会被算进<b>基础值</b>。
     * 但它们本质是第三方给的额外增益，应当显示在「结果（基础值 + 非基础值）」
     * 的<b>括号那一段</b>里，也就是提升值。
     *
     * <h2>运算方式的映射</h2>
     * 外部模块用的是「加固定值 / 加百分比」这套语义，而本 mod 把提升值拆成
     * {@code *_flat} 与 {@code *_percent} 两个属性，因此：
     * <ul>
     *   <li>{@code ADD_VALUE} → 挂到 {@code *_flat}（固定值）；</li>
     *   <li>乘算类 → 挂到 {@code *_percent}，并以加算方式表达百分比。</li>
     * </ul>
     * 也就是说外部的那一份最终都是「加到提升值上」，不会被塞进基础值。
     *
     * @param flatTarget    固定值属性（可为 null，则跳过）
     * @param percentTarget 百分比属性（可为 null，则跳过）
     * @param source        原版属性实例
     * @param prefix        镜像前缀（用于识别与清理）
     */
    public static void mirrorExternalToBonus(AttributeInstance flatTarget,
                                             AttributeInstance percentTarget,
                                             AttributeInstance source,
                                             String prefix) {
        if (flatTarget != null) {
            removeMirrors(flatTarget, prefix);
        }
        if (percentTarget != null) {
            removeMirrors(percentTarget, prefix);
        }
        if (source == null) {
            return;
        }

        int index = 0;
        for (AttributeModifier modifier : source.getModifiers()) {
            if (!isExternal(modifier)) {
                continue;
            }

            AttributeInstance target = modifier.operation() == AttributeModifier.Operation.ADD_VALUE
                    ? flatTarget
                    : percentTarget;
            if (target == null) {
                continue;
            }

            ResourceLocation mirrorId = ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, prefix + index++);

            // 统一以 ADD_VALUE 叠加：
            // 外部那三种运算方式在这个 mod 里都归约为「提升值上加一份」。
            AttributeModifier mirror = new AttributeModifier(
                    mirrorId, modifier.amount(), AttributeModifier.Operation.ADD_VALUE);

            try {
                target.addOrUpdateTransientModifier(mirror);
            } catch (Exception e) {
                DamageModernization.LOGGER.debug(
                        "Failed to mirror external modifier {} onto {}", modifier.id(), prefix, e);
            }
        }
    }

    /**
     * 清除目标属性上所有带指定前缀的镜像修饰符。
     *
     * @param target 目标属性
     * @param prefix 镜像前缀
     */
    public static void removeMirrors(AttributeInstance target, String prefix) {
        if (target == null) {
            return;
        }

        // 先收集再删除，避免在遍历过程中修改集合。
        List<ResourceLocation> toRemove = new ArrayList<>();
        for (AttributeModifier modifier : target.getModifiers()) {
            if (isMirror(modifier, prefix)) {
                toRemove.add(modifier.id());
            }
        }
        for (ResourceLocation id : toRemove) {
            target.removeModifier(id);
        }
    }

    /**
     * {@return 该修饰符是否属于指定前缀的镜像}
     *
     * @param modifier 修饰符
     * @param prefix   镜像前缀
     */
    public static boolean isMirror(AttributeModifier modifier, String prefix) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID)
                && modifier.id().getPath().startsWith(prefix);
    }

    /**
     * {@return 该修饰符是否由本 mod 写入}
     *
     * <p>凡是本 mod 命名空间下的修饰符都不应被镜像——
     * 它们要么是镜像产物（复制会自我繁殖），
     * 要么是计算结果（复制会把它当成原始数据重复计算）。
     *
     * @param modifier 修饰符
     */
    public static boolean isOurs(AttributeModifier modifier) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID);
    }
}
