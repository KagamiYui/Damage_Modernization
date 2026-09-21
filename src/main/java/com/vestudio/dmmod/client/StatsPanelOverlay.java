package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;
import com.vestudio.dmmod.util.StatFormat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 属性面板：把本 mod 的全部属性以「基础值 + 提升值」的形式展示出来。
 *
 * <h2>显示格式</h2>
 * <pre>
 *   属性名称   总值（基础值+提升值）
 * </pre>
 * 例如：
 * <pre>
 *   基础攻击力          10（4+6）
 *   暴击率              5%（5%+0%）
 * </pre>
 *
 * <h2>为什么用「总值（基础+提升）」而不是只显示总值</h2>
 * 四乘区模型下，玩家最需要判断的是「这个加成到底加在哪一层」。
 * 拆成基础值与提升值后，一眼就能看出某个加成是来自武器基础值，
 * 还是来自装备/药水提供的修饰符。
 *
 * <h2>数值格式化</h2>
 * 数值统一通过属性自身的 {@code toValueComponent} 渲染，
 * 因此百分比类属性（暴击率、暴击伤害等）会自动显示成百分比，
 * 与 tooltip 中的显示完全一致，不会出现两处对不上的情况。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class StatsPanelOverlay {

    /** 面板在屏幕上的定位锚点（注册到 HUD 图层之上）。 */
    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DamageModernization.MODID, "stats_panel");

    // ---- 配色 ----
    private static final int COLOR_BACKGROUND = 0xC8101014;
    private static final int COLOR_BORDER = 0xFF5A5A66;
    private static final int COLOR_TITLE = 0xFFFFD479;
    private static final int COLOR_NAME = 0xFFE0E0E0;
    private static final int COLOR_VALUE = 0xFF8BE28B;
    private static final int COLOR_SEPARATOR = 0xFF3A3A44;

    /** 面板内边距。 */
    private static final int PADDING = 6;
    /** 行高。 */
    private static final int LINE_HEIGHT = 11;
    /** 名称列与数值列之间的最小间距。 */
    private static final int COLUMN_GAP = 12;

    private StatsPanelOverlay() {
    }

    /**
     * 注册 HUD 图层。
     *
     * <p>注册在 {@link VanillaGuiLayers#HOTBAR} 之上，
     * 确保面板覆盖在物品栏等原版 HUD 之上而不被遮挡。
     *
     * @param event 图层注册事件
     */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID,
                (guiGraphics, deltaTracker) -> render(guiGraphics));
    }

    /**
     * 渲染面板。
     *
     * @param graphics 绘制上下文
     */
    private static void render(GuiGraphics graphics) {
        if (!StatsPanelKeybind.isVisible()) {
            // 面板关闭时释放缓存，下次打开重新取值。
            clearCache();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // 不在游戏内（例如主菜单、加载中）或玩家尚未就绪时不绘制。
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            clearCache();
            return;
        }

        CachedPanel panel = getPanel(player);
        if (panel == null) {
            return;
        }

        Font font = minecraft.font;
        List<Row> rows = panel.rows();
        Component title = Component.translatable("gui." + DamageModernization.MODID + ".stats_panel.title");

        // ---- 面板尺寸（沿用缓存中量好的宽度，避免每帧重新测量）----
        int titleWidth = font.width(title);
        int contentWidth = Math.max(panel.nameWidth() + COLUMN_GAP + panel.valueWidth(), titleWidth);

        int panelWidth = contentWidth + PADDING * 2;
        int panelHeight = PADDING * 2 + LINE_HEIGHT * 2 + rows.size() * LINE_HEIGHT;

        // ---- 定位：屏幕右侧偏上 ----
        int x = graphics.guiWidth() - panelWidth - 6;
        int y = 6;

        // 背景与边框
        graphics.fill(x, y, x + panelWidth, y + panelHeight, COLOR_BACKGROUND);
        graphics.fill(x, y, x + panelWidth, y + 1, COLOR_BORDER);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + panelHeight, COLOR_BORDER);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, COLOR_BORDER);

        int cursorY = y + PADDING;

        // 标题
        graphics.drawString(font, title, x + PADDING, cursorY, COLOR_TITLE, true);
        cursorY += LINE_HEIGHT;

        // 标题下的分隔线
        graphics.fill(x + PADDING, cursorY - 2, x + panelWidth - PADDING, cursorY - 1, COLOR_SEPARATOR);
        cursorY += 2;

        // 属性行：名称左对齐，数值右对齐，保证数值列整齐。
        int valueColumnX = x + panelWidth - PADDING - panel.valueWidth();
        for (Row row : rows) {
            graphics.drawString(font, row.name(), x + PADDING, cursorY, COLOR_NAME, true);
            graphics.drawString(font, row.value(), valueColumnX, cursorY, COLOR_VALUE, true);
            cursorY += LINE_HEIGHT;
        }
    }

    // ==================================================================
    // 面板缓存
    // ==================================================================

    /**
     * 缓存的面板内容。
     *
     * @param rows       属性行
     * @param values     构成这些行的原始数值，用于判断是否需要重建
     * @param nameWidth  名称列最大宽度
     * @param valueWidth 数值列最大宽度
     */
    private record CachedPanel(List<Row> rows, double[] values, int nameWidth, int valueWidth) {
    }

    /** 上一次构建的面板；为 null 表示尚无缓存。 */
    private static CachedPanel cachedPanel = null;

    /** 帧计数，用于把「取值比较」也降低到每若干帧一次。 */
    private static int frameCounter = 0;

    /**
     * 取值比较的间隔帧数。
     *
     * <p>数值最多每秒（服务端刷新间隔）变化一次，
     * 因此无需每帧都去读取属性；每 5 帧比较一次，
     * 即使在 60 FPS 下延迟也不足 0.1 秒，肉眼无法察觉。
     */
    private static final int VALUES_CHECK_INTERVAL_FRAMES = 5;

    /**
     * 取得当前应显示的面板内容（带缓存）。
     *
     * <h2>为什么需要缓存</h2>
     * GUI 每帧都会重绘。若每帧都重新读取属性、重新格式化文本并重新测量宽度，
     * 在数值根本没变的情况下全是白做的开销。
     *
     * <p>因此这里先取出构成面板的<b>原始数值</b>做比较：
     * 与上次完全一致时直接复用缓存的排版结果，
     * 只有确实变化了才重建行与宽度。
     *
     * @param player 本地玩家
     * @return 面板内容；无内容可显示时返回 null
     */
    private static CachedPanel getPanel(LocalPlayer player) {
        // 上一个面板存在时：每若干帧比较一次数值，未变化就直接复用。
        if (cachedPanel != null && frameCounter++ % VALUES_CHECK_INTERVAL_FRAMES != 0) {
            return cachedPanel;
        }

        double[] values = readValues(player);

        if (cachedPanel != null && Arrays.equals(cachedPanel.values(), values)) {
            // 数值没变：不重建、不重新排版、不重新测量。
            return cachedPanel;
        }

        List<Row> rows = new ArrayList<>();
        buildRows(rows, player);
        if (rows.isEmpty()) {
            cachedPanel = null;
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int nameWidth = 0;
        int valueWidth = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, font.width(row.name()));
            valueWidth = Math.max(valueWidth, font.width(row.value()));
        }

        cachedPanel = new CachedPanel(rows, values, nameWidth, valueWidth);
        return cachedPanel;
    }

    /**
     * 读取构成面板的全部原始数值。
     *
     * <p>只取值、不排版，因此非常轻量，适合频繁比较。
     *
     * @param player 本地玩家
     * @return 数值数组
     */
    private static double[] readValues(LocalPlayer player) {
        return new double[] {
                // 生命值体系
                attributeValue(player, DMAttributes.BASE_HEALTH),
                attributeValue(player, DMAttributes.HEALTH_PERCENT),
                attributeValue(player, DMAttributes.HEALTH_FLAT),
                // 攻击力体系
                attributeValue(player, DMAttributes.BASE_ATTACK_POWER),
                attributeValue(player, DMAttributes.ATTACK_POWER_PERCENT),
                attributeValue(player, DMAttributes.ATTACK_POWER_FLAT),
                // 其余乘区
                attributeValue(player, DMAttributes.DAMAGE_AMPLIFIER),
                attributeValue(player, DMAttributes.DAMAGE_MULTIPLIER),
                attributeValue(player, DMAttributes.CRIT_CHANCE),
                attributeValue(player, DMAttributes.CRIT_DAMAGE),
        };
    }

    /**
     * 读取属性值；属性不存在时返回 0。
     *
     * @param player    本地玩家
     * @param attribute 属性
     * @return 属性值
     */
    private static double attributeValue(LocalPlayer player, Holder<Attribute> attribute) {
        return player.getAttribute(attribute) == null ? 0.0D : player.getAttributeValue(attribute);
    }

    /** 清空缓存。 */
    private static void clearCache() {
        cachedPanel = null;
    }

    /**
     * 收集要显示的全部属性行。
     *
     * <p>每个属性被拆成「基础值」与「提升值」两部分：
     * <ul>
     *   <li>基础值 = {@code AttributeInstance.getBaseValue()}，
     *       由原版攻击伤害迁移或属性默认值决定；</li>
     *   <li>提升值 = 总值 − 基础值，即所有修饰符叠加后的净贡献。</li>
     * </ul>
     *
     * @param player 本地玩家
     * @param font   字体，用于后续宽度计算
     * @return 属性行列表
     */
    private static void buildRows(List<Row> rows, LocalPlayer player) {
        // 生命值与攻击力同属「有基础值的成长体系」，因此都显示
        // 「结果（基础值 + 非基础值）」。
        addHealthRow(rows, player);
        addAttackPowerZoneRow(rows, player);

        // 其余乘区没有分开的基础值，只显示「结果（增加量）」；
        // 没有增加量时连括号一并省略。
        addBonusOnlyRow(rows, player, DMAttributes.DAMAGE_AMPLIFIER);
        addBonusOnlyRow(rows, player, DMAttributes.DAMAGE_MULTIPLIER);
        addBonusOnlyRow(rows, player, DMAttributes.CRIT_CHANCE);
        addBonusOnlyRow(rows, player, DMAttributes.CRIT_DAMAGE);
    }

    /**
     * 生成「生命值」这一行。
     *
     * <p>与攻击力区同一套逻辑：
     * <pre>
     *   结果 = 基础生命值 × (1 + 生命值百分比提升) + 固定生命值
     *   非基础生命值 = 结果 − 基础生命值
     * </pre>
     * 基础生命值由原版 max_health 镜像而来（含装备加成），
     * 因此显示为「结果（基础生命值 + 非基础生命值）」。
     *
     * @param rows   结果列表
     * @param player 本地玩家
     */
    private static void addHealthRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> baseAttr = DMAttributes.BASE_HEALTH;

        if (player.getAttribute(baseAttr) == null) {
            return;
        }

        // 基础生命值：已含装备等提供的生命加成。
        double baseHealth = player.getAttributeValue(baseAttr);

        // 百分比与固定加值。
        double percent = player.getAttribute(DMAttributes.HEALTH_PERCENT) == null
                ? 0.0D
                : player.getAttributeValue(DMAttributes.HEALTH_PERCENT);
        double flat = player.getAttribute(DMAttributes.HEALTH_FLAT) == null
                ? 0.0D
                : player.getAttributeValue(DMAttributes.HEALTH_FLAT);

        // 该体系最终血量 = 基础生命值 × (1 + 百分比) + 固定值。
        double total = baseHealth * (1.0D + percent) + flat;

        // 非基础部分 = 结果 − 基础生命值。
        double nonBase = total - baseHealth;

        String name = Component.translatable(baseAttr.value().getDescriptionId()).getString();

        // 与攻击力同样的排版：结果（基础值 + 非基础值）。
        String value = StatFormat.basePlusBonus(DMAttributes.BASE_HEALTH, total, baseHealth, nonBase);

        rows.add(new Row(name, value));
    }

    /**
     * 生成「攻击力区」这一行。
     *
     * <p>该乘区由三个属性共同决定：{@code 基础攻击力}、{@code 攻击力百分比提升}、
     * {@code 固定攻击力}。三者同属一个乘区，因此合并为<b>一行</b>展示。
     *
     * <h2>显示格式</h2>
     * <pre>
     *   攻击力   结果（基础攻击力 + 非基础攻击力）
     * </pre>
     *
     * <h2>基础攻击力包含武器</h2>
     * 武器的攻击伤害会被换算并计入基础攻击力（空手 1、钻石剑 7），
     * 因此加号<b>前面</b>的是手持当前武器后的基础攻击力，
     * 加号<b>后面</b>才是非基础的那部分（百分比提升与固定攻击力带来的点数）：
     * <pre>
     *   结果 = 基础攻击力 × (1 + 百分比提升) + 固定攻击力
     *   非基础攻击力 = 结果 − 基础攻击力
     * </pre>
     *
     * @param rows   结果列表
     * @param player 本地玩家
     */
    private static void addAttackPowerZoneRow(List<Row> rows, LocalPlayer player) {
        Holder<Attribute> baseAttr = DMAttributes.BASE_ATTACK_POWER;

        // 基础攻击力缺失时不显示该行（属性被其他模组移除的极端情况）。
        if (player.getAttribute(baseAttr) == null) {
            return;
        }

        // 基础攻击力：属性总值即包含武器贡献（空手 1、钻石剑 7），
        // 注意不是 getAttributeBaseValue()——那只是不含武器的基准 1。
        double baseAttackPower = player.getAttributeValue(baseAttr);

        // 百分比与固定加值；属性缺失时按 0 处理，不影响其余计算。
        double percent = player.getAttribute(DMAttributes.ATTACK_POWER_PERCENT) == null
                ? 0.0D
                : player.getAttributeValue(DMAttributes.ATTACK_POWER_PERCENT);
        double flat = player.getAttribute(DMAttributes.ATTACK_POWER_FLAT) == null
                ? 0.0D
                : player.getAttributeValue(DMAttributes.ATTACK_POWER_FLAT);

        // 该乘区最终点数 = 基础攻击力 × (1 + 百分比) + 固定值。
        double zoneTotal = baseAttackPower * (1.0D + percent) + flat;

        // 非基础攻击力 = 结果 − 基础攻击力（百分比换算出的点数 + 固定值）。
        double nonBase = zoneTotal - baseAttackPower;

        String name = Component.translatable("gui." + DamageModernization.MODID + ".attack_power_zone")
                .getString();

        // 排版为「结果（基础攻击力 + 非基础攻击力）」；无非基础部分时省略括号。
        String value = StatFormat.attackPowerValue(zoneTotal, baseAttackPower, nonBase);

        rows.add(new Row(name, value));
    }

    /**
     * 生成「只显示增加量」的乘区行。
     *
     * <p>适用于没有独立基础值的乘区（伤害提升、伤害倍率、暴击率、暴击伤害）：
     * <pre>
     *   结果（增加量）
     * </pre>
     * 增加量以百分比呈现且不带 {@code +} 号；<b>数值没有变动时省略括号</b>。
     *
     * @param rows      结果列表
     * @param player    本地玩家
     * @param attribute 属性
     */
    private static void addBonusOnlyRow(List<Row> rows,
                                        LocalPlayer player,
                                        Holder<Attribute> attribute) {
        if (player.getAttribute(attribute) == null) {
            return;
        }

        double total = player.getAttributeValue(attribute);
        // 以属性默认值为基准判断「是否变动」：
        // 例如伤害倍率默认 1.0、暴击率默认 0.0，没有加成时就不显示括号。
        double baseline = attribute.value().getDefaultValue();
        double delta = total - baseline;

        String name = Component.translatable(attribute.value().getDescriptionId()).getString();

        // 排版为「结果（增加量）」；无变动时省略括号。
        String value = StatFormat.bonusOnlyValue(attribute, total, delta);

        rows.add(new Row(name, value));
    }

    /**
     * 面板中的一行。
     *
     * @param name  属性名称
     * @param value 格式化后的数值文本
     */
    private record Row(String name, String value) {
    }
}
