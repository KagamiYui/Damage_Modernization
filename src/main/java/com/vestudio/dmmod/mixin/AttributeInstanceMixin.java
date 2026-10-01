package com.vestudio.dmmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vestudio.dmmod.damage.AttributePostProcess;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * 把原版属性的「计算」与「输出」分开。
 *
 * <pre>
 *   原版计算（calculateValue）→ 原版值 → 我们的变换 → 交出去的值
 * </pre>
 *
 * <h2>为什么挂在 getValue 而不是 calculateValue</h2>
 * 本来更合适的位置是 {@code calculateValue} 的返回点（只在脏标记时才算，更省），
 * 但实测发现那个点<b>轮不到我们</b>：
 *
 * <ul>
 *   <li>星辉（Astral Sorcery）的 {@code MixinAttributeInstance} 也注入在
 *       {@code calculateValue} 的返回点，并且<b>无条件</b>调用 {@code setReturnValue}——
 *       在 Mixin 里这等同于 {@code cancel()}，它一执行，
 *       排在它后面的注入<b>全部被跳过</b>；</li>
 *   <li>实测：同一个类里 {@code calculateValue} 的 {@code @At("HEAD")} 回调执行了 1048 次，
 *       而 {@code @At("RETURN")} 回调<b>一次都没有</b>；显式把优先级提到 1500 也无效；</li>
 *   <li>而 {@code getValue} 上没有任何 mod 争抢，
 *       同一份代码在那里<a>正常工作</a>。</li>
 * </ul>
 *
 * <p>{@code getValue} 也正是「对外输出」的那一步，语义上更贴合
 * 「计算归原版、输出归我们」的分工。
 *
 * <h2>代价</h2>
 * {@code getValue} 是高频方法（实测十几秒内被调用六十余万次），
 * 因此后处理必须是<b>极轻的纯变换</b>——几次算术以内，不遍历、不查表、
 * 不读取其它属性。任何重一点的逻辑都应该放到脏标记驱动的路径上去。
 *
 * <h2>为什么用 @ModifyReturnValue</h2>
 * 它只替换返回值而<b>不取消</b>调用，因此不会把后续其它 mod 的注入挤掉——
 * 这正是星辉那种写法造成的问题，我们不能重复它。
 */
@Mixin(value = AttributeInstance.class, priority = 1500)
public abstract class AttributeInstanceMixin {

    /**
     * 在原版算出属性值之后接一手，把结果交给我们的后处理。
     *
     * @param original 原版算出的值
     * @return 要交出去的值
     */
    @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
    private double damageModernization$postProcessValue(double original) {
        // Mixin 织入后 this 就是目标类型，但编译期看不到这层关系，需要绕一下。
        AttributeInstance self = (AttributeInstance) (Object) this;
        return AttributePostProcess.apply(self, original);
    }
}
