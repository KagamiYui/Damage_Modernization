package com.vestudio.dmmod.damage;

import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
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

        // 复用统一的镜像逻辑：搬迁基础值 + 按前缀重建修饰符。
        AttributeMirror.mirror(baseAttr, vanillaAttr, AttributeMirror.ATTACK_POWER_PREFIX);

        return sanitize(baseAttr.getValue(), sanitize(vanillaAttr.getValue(), 1.0D));
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
        AttributeMirror.removeMirrors(
                entity.getAttribute(DMAttributes.BASE_ATTACK_POWER),
                AttributeMirror.ATTACK_POWER_PREFIX);
    }

    /**
     * {@return 合法化后的数值，非法或负数时返回 fallback}
     */
    private static double sanitize(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0D ? value : fallback;
    }
}
