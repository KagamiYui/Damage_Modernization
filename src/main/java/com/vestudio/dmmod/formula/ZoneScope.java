package com.vestudio.dmmod.formula;

import net.minecraft.resources.ResourceLocation;

/**
 * 乘区所属的<b>体系</b>。
 *
 * <h2>为什么需要「体系」这一层</h2>
 * 「乘区」在本 mod 里是<b>通用的公式单元</b>，并不专指伤害。
 * 任何需要「多个可配置公式协同算出一个结果」的地方都可以有自己的乘区集合。
 *
 * <p>目前有三套并列的体系：
 * <ul>
 *   <li>{@link #DAMAGE}：伤害公式的乘区——攻击力区、伤害提升区、伤害倍率区、暴击区，
 *       四者连乘得到最终伤害；</li>
 *   <li>{@link #TAKEN}：承伤公式的乘区——受到伤害时的减免与易伤，
 *       输出乘数叠加在伤害公式的结果之上；</li>
 *   <li>{@link #HEALTH}：生命值的乘区——基础生命值缩放、生命值提升，
 *       算出最终最大生命值。</li>
 * </ul>
 *
 * <p>三套体系<b>各自独立、互不参与</b>：生命值乘区不会进入伤害的连乘链，
 * 承伤乘区也不会回头改写攻击方的乘区（它只作用在结果之上）。
 * 以后若还需要别的体系，再加一个作用域即可。
 *
 * <p>由于有了这一层，两套体系的乘区可以共用同一个注册表而互不干扰。
 */
public enum ZoneScope {

    /** 伤害公式的乘区（攻击方：这一下打得多疼）。 */
    DAMAGE("damage"),

    /** 承伤公式的乘区（防守方：这一下挨得多轻）。 */
    TAKEN("taken"),

    /** 生命值的乘区。 */
    HEALTH("health");

    private final String id;

    ZoneScope(String id) {
        this.id = id;
    }

    /** {@return 数据文件中使用的标识} */
    public String id() {
        return id;
    }

    /**
     * 按标识解析作用域。
     *
     * @param id 标识
     * @return 对应作用域
     * @throws FormulaEngine.FormulaException 标识未知时抛出
     */
    public static ZoneScope byId(String id) {
        for (ZoneScope scope : values()) {
            if (scope.id.equalsIgnoreCase(id)) {
                return scope;
            }
        }
        throw new FormulaEngine.FormulaException("未知作用域: " + id);
    }

    /**
     * {@return 用于注册表的资源位置}
     *
     * @param path 乘区路径
     */
    public ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath(
                com.vestudio.dmmod.DamageModernization.MODID, id + "/" + path);
    }
}
