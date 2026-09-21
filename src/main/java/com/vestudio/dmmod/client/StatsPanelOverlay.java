package com.vestudio.dmmod.client;

import java.util.ArrayList;
import java.util.List;

import com.vestudio.dmmod.DamageModernization;
import com.vestudio.dmmod.api.DMAttributes;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.TooltipFlag;
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
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // 不在游戏内（例如主菜单、加载中）或玩家尚未就绪时不绘制。
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            return;
        }

        Font font = minecraft.font;
        List<Row> rows = collectRows(player, font);
        if (rows.isEmpty()) {
            return;
        }

        Component title = Component.translatable("gui." + DamageModernization.MODID + ".stats_panel.title");

        // ---- 计算面板尺寸 ----
        int nameWidth = 0;
        int valueWidth = 0;
        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, font.width(row.name()));
            valueWidth = Math.max(valueWidth, font.width(row.value()));
        }

        int titleWidth = font.width(title);
        int contentWidth = Math.max(nameWidth + COLUMN_GAP + valueWidth, titleWidth);

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
        int valueColumnX = x + panelWidth - PADDING - valueWidth;
        for (Row row : rows) {
            graphics.drawString(font, row.name(), x + PADDING, cursorY, COLOR_NAME, true);
            graphics.drawString(font, row.value(), valueColumnX, cursorY, COLOR_VALUE, true);
            cursorY += LINE_HEIGHT;
        }
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
    private static List<Row> collectRows(LocalPlayer player, Font font) {
        List<Row> rows = new ArrayList<>();

        addRow(rows, player, DMAttributes.BASE_ATTACK_POWER);
        addRow(rows, player, DMAttributes.ATTACK_POWER_PERCENT);
        addRow(rows, player, DMAttributes.ATTACK_POWER_FLAT);
        addRow(rows, player, DMAttributes.DAMAGE_AMPLIFIER);
        addRow(rows, player, DMAttributes.DAMAGE_MULTIPLIER);
        addRow(rows, player, DMAttributes.CRIT_CHANCE);
        addRow(rows, player, DMAttributes.CRIT_DAMAGE);

        return rows;
    }

    /**
     * 把单个属性格式化为一行。
     *
     * <p>若该属性不存在于玩家身上（例如被其他模组移除了注入），则跳过，
     * 而不是显示 0 造成误解。
     *
     * @param rows      结果列表
     * @param player    本地玩家
     * @param attribute 属性
     */
    private static void addRow(List<Row> rows, LocalPlayer player, Holder<Attribute> attribute) {
        if (player.getAttribute(attribute) == null) {
            return;
        }

        double total = player.getAttributeValue(attribute);
        double base = player.getAttributeBaseValue(attribute);
        double bonus = total - base;

        String name = Component.translatable(attribute.value().getDescriptionId()).getString();
        String valueText = format(attribute, total, base, bonus);

        rows.add(new Row(name, valueText));
    }

    /**
     * 按「总值（基础值+提升值）」格式生成文本。
     *
     * <p>三处数值都经由属性自身的显示逻辑渲染，
     * 因此百分比类属性会自动带上 {@code %}，与 tooltip 保持一致。
     *
     * <p>提升值仅在非零时显示正负号，零值显示为 {@code +0} 以保持格式完整。
     *
     * @param attribute 属性（用于调用其格式化逻辑）
     * @param total     总值
     * @param base      基础值
     * @param bonus     提升值
     * @return 格式化后的文本
     */
    private static String format(Holder<Attribute> attribute, double total, double base, double bonus) {
        String totalText = render(attribute, total);
        String baseText = render(attribute, base);
        String bonusText = render(attribute, bonus);

        // 提升值统一带符号，便于区分正负收益。
        if (bonus > 0.0D && !bonusText.startsWith("+")) {
            bonusText = "+" + bonusText;
        }

        return totalText + "（" + baseText + "+" + bonusText + "）";
    }

    /**
     * 调用属性自身的显示逻辑渲染一个数值。
     *
     * <p>传 {@code null} 运算类型，表示「显示数值本身」而非修饰符，
     * 这正是属性面板需要的语义。
     *
     * @param attribute 属性
     * @param value     数值
     * @return 渲染后的字符串
     */
    private static String render(Holder<Attribute> attribute, double value) {
        return attribute.value()
                .toValueComponent(null, value, TooltipFlag.NORMAL)
                .getString();
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
