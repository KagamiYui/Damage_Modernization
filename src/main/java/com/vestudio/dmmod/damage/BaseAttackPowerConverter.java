package com.vestudio.dmmod.damage;

import java.util.ArrayList;
import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 负责把原版的「攻击伤害（attack_damage）」重写为「基础攻击力（base_attack_power）」。
 *
 * <h2>为什么需要「重写」而不是「改键名」</h2>
 * 原版 {@code generic.attack_damage} 的语义是<b>最终伤害</b>：读到这个值之后，
 * 加上蓄力与暴击就直接扣血。若只是把它的显示名改成「基础攻击力」，
 * 「百分比提升攻击力」依旧会作用在最终伤害上，乘区模型就名不副实。
 *
 * <p>本类把 attack_damage 的数值语义整体迁移到 base_attack_power：
 * <ol>
 *   <li><b>基础值搬迁</b>：把 attack_damage 的 baseValue 搬到 base_attack_power 的 baseValue；</li>
 *   <li><b>修饰符镜像</b>：把 attack_damage 上的修饰符（武器加成、药水、装备等）
 *       以同样的数值与运算方式镜像到 base_attack_power。</li>
 * </ol>
 *
 * <p>两步合起来等价于：{@code base_attack_power 的最终值 == attack_damage 的最终值}，
 * 因此「空手 1 + 石剑 3 = 4」这类原版数值关系被完整保留，
 * 同时武器加值、装备加成、药水效果都会被正确继承。
 *
 * <p><b>关键点</b>：搬运的是 baseValue、镜像的是 modifier，
 * 二者互不重叠，因此不会把武器加成算两遍。这也是本类与
 * 「直接复制最终值」做法的本质区别。
 *
 * <h2>兼容性</h2>
 * 原版 attack_damage 及其修饰符<b>不会被移除</b>，以免破坏其他仍在读取它的系统
 * （生物 AI、第三方模组的兼容逻辑等）。本类只是在其旁边维护一份
 * 「语义为标准攻击力」的副本供四乘区使用。
 */
public final class BaseAttackPowerConverter {

    /** 镜像修饰符的 id 前缀，用于识别并清理本类生成的修饰符。 */
    private static final String MIRROR_PREFIX = "dm_base_mirror_";

    private BaseAttackPowerConverter() {
    }

    /**
     * 把实体的原版攻击伤害完整转换为基础攻击力。
     *
     * <p>该方法<b>幂等</b>：重复调用只会得到同样结果，不会累加。
     * 内部先同步基础值，再重建镜像修饰符。
     *
     * @param entity 目标实体
     * @return 转换后的基础攻击力最终值；属性缺失时回退到原版攻击伤害
     */
    public static double resolveBaseAttackPower(LivingEntity entity) {
        AttributeInstance baseAttr = entity.getAttribute(DMAttributes.BASE_ATTACK_POWER);
        AttributeInstance vanillaAttr = entity.getAttribute(Attributes.ATTACK_DAMAGE);

        // 实体没有攻击伤害属性（如盔甲架）时，按空手基准 1.0 处理，
        // 这样它仍能安全地走四乘区管线而不会产生 NaN。
        if (vanillaAttr == null) {
            return baseAttr != null ? sanitize(baseAttr.getValue(), 1.0D) : 1.0D;
        }

        // 实体没有我们注入的基础攻击力属性时，直接回退到原版数值，
        // 保证第三方实体不会因为缺少属性而行为异常。
        if (baseAttr == null) {
            return sanitize(vanillaAttr.getValue(), 1.0D);
        }

        syncBaseValue(baseAttr, vanillaAttr);
        mirrorModifiers(entity, baseAttr, vanillaAttr);

        return sanitize(baseAttr.getValue(), sanitize(vanillaAttr.getValue(), 1.0D));
    }

    /**
     * 搬迁基础值：base_attack_power.baseValue ← attack_damage.baseValue。
     *
     * <p>基础值承载的是「武器/生物自身的固有攻击力」，
     * 例如玩家基础值 1.0、僵尸基础值 3.0。
     */
    private static void syncBaseValue(AttributeInstance baseAttr, AttributeInstance vanillaAttr) {
        double vanillaBase = vanillaAttr.getBaseValue();
        if (!Double.isFinite(vanillaBase) || vanillaBase < 0.0D) {
            return;
        }
        // 仅在确有差异时写入，避免每 tick 触发属性脏标记与网络同步。
        if (Math.abs(baseAttr.getBaseValue() - vanillaBase) > 1.0E-6D) {
            baseAttr.setBaseValue(vanillaBase);
        }
    }

    /**
     * 镜像修饰符：把 attack_damage 上的修饰符复制到 base_attack_power。
     *
     * <h2>清理策略：按前缀清除，而不是靠记忆</h2>
     * 早期实现把「上一轮添加了哪些修饰符」记在一个按实体 UUID 索引的静态表里，
     * 下次据此删除。这种做法在换手、切维度、死亡重生、以及客户端/服务端
     * 各自维护一份表的情况下都可能失效，导致<b>旧武器的加成残留在空手上</b>。
     *
     * <p>现在改为：每轮先把 {@code base_attack_power} 上所有带镜像前缀的修饰符
     * 一并清除，再按当前武器重建。这样无论上一轮处于什么状态，
     * 都不会有残留——不依赖任何跨调用的记忆。
     */
    private static void mirrorModifiers(LivingEntity entity,
                                        AttributeInstance baseAttr,
                                        AttributeInstance vanillaAttr) {
        // 清除本类此前添加的所有镜像修饰符（按前缀识别）。
        removeAllMirrors(baseAttr);

        int index = 0;

        for (AttributeModifier modifier : vanillaAttr.getModifiers()) {
            // 跳过本类自己的镜像，避免自我复制造成指数增长。
            if (isMirror(modifier)) {
                continue;
            }

            ResourceLocation mirrorId = ResourceLocation.fromNamespaceAndPath(
                    DamageModernization.MODID, MIRROR_PREFIX + index++);

            AttributeModifier mirror = new AttributeModifier(
                    mirrorId, modifier.amount(), modifier.operation());

            try {
                baseAttr.addOrUpdateTransientModifier(mirror);
            } catch (Exception e) {
                // 单个修饰符失败不应中断整体结算，仅记录调试日志。
                DamageModernization.LOGGER.debug(
                        "Failed to mirror modifier {} onto base_attack_power",
                        modifier.id(), e);
            }
        }
    }

    /**
     * 清除基础攻击力上所有由本类添加的镜像修饰符。
     *
     * @param baseAttr 基础攻击力属性实例
     */
    public static void removeAllMirrors(AttributeInstance baseAttr) {
        if (baseAttr == null) {
            return;
        }

        // 先收集再删除，避免在遍历过程中修改集合。
        List<ResourceLocation> toRemove = new ArrayList<>();
        for (AttributeModifier modifier : baseAttr.getModifiers()) {
            if (isMirror(modifier)) {
                toRemove.add(modifier.id());
            }
        }
        for (ResourceLocation id : toRemove) {
            baseAttr.removeModifier(id);
        }
    }

    /**
     * {@return 该修饰符是否由本类镜像生成}
     *
     * @param modifier 修饰符
     */
    private static boolean isMirror(AttributeModifier modifier) {
        return modifier.id().getNamespace().equals(DamageModernization.MODID)
                && modifier.id().getPath().startsWith(MIRROR_PREFIX);
    }

    /**
     * 读取原版攻击伤害的最终值。
     *
     * <p>用于在没有自定义属性时回退，以及在日志中对照。
     *
     * @param entity 目标实体
     * @return 原版攻击伤害；缺失或非法时返回 1.0
     */
    public static double vanillaAttackDamage(LivingEntity entity) {
        AttributeInstance vanilla = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (vanilla == null) {
            return 1.0D;
        }
        return sanitize(vanilla.getValue(), 1.0D);
    }

    /**
     * 清除某个实体基础攻击力上的镜像修饰符。
     *
     * <p>应在实体死亡或卸载时调用，把镜像状态一并清干净，
     * 避免实体被复用或重新加入时残留旧武器的加成。
     *
     * @param entity 目标实体
     */
    public static void forget(LivingEntity entity) {
        removeAllMirrors(entity.getAttribute(DMAttributes.BASE_ATTACK_POWER));
    }

    /**
     * {@return 合法化后的数值，非法或负数时返回 fallback}
     */
    private static double sanitize(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0D ? value : fallback;
    }
}
