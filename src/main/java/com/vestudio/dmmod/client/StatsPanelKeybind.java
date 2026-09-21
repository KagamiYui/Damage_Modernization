package com.vestudio.dmmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vestudio.dmmod.DamageModernization;

import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * 属性面板的按键绑定与显隐状态。
 *
 * <p>面板默认关闭，玩家按快捷键（默认 <b>K</b>）才会显示，
 * 以免在正常游玩时遮挡视野。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class StatsPanelKeybind {

    /** 按键分类的名称键，显示在「按键绑定」设置界面中。 */
    public static final String CATEGORY = "key.categories." + DamageModernization.MODID;

    /**
     * 打开/关闭属性面板的按键。
     *
     * <p>使用 {@link KeyConflictContext#INGAME}，因此只有在游戏内（而非打开菜单时）
     * 才会触发，避免与界面操作冲突。
     */
    public static final KeyMapping TOGGLE_PANEL = new KeyMapping(
            "key." + DamageModernization.MODID + ".toggle_stats_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            CATEGORY);

    /** 面板当前是否可见。 */
    private static boolean visible = false;

    private StatsPanelKeybind() {
    }

    /**
     * 注册按键绑定。
     *
     * @param event 按键注册事件（mod 事件总线）
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_PANEL);
    }

    /**
     * 每个客户端 tick 检查按键是否被按下。
     *
     * <p>使用 {@code consumeClick()} 而非 {@code isDown()}，
     * 这样按一次只切换一次，不会因为按住而反复闪烁。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 同一个按键可能累积多次点击，循环消费直到取空。
        while (TOGGLE_PANEL.consumeClick()) {
            visible = !visible;
        }
    }

    /** {@return 面板是否可见} */
    public static boolean isVisible() {
        return visible;
    }

    /**
     * 设置面板可见性。
     *
     * @param value 是否可见
     */
    public static void setVisible(boolean value) {
        visible = value;
    }
}
