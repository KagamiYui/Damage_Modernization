package com.vestudio.dmmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.vestudio.dmmod.DamageModernization;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * 属性面板的按键绑定。
 *
 * <p>面板默认关闭，按快捷键（默认 <b>K</b>）打开一个<b>独立界面</b>；
 * 在界面里再按一次 K、或按 ESC 即可关闭。
 */
@EventBusSubscriber(modid = DamageModernization.MODID, value = Dist.CLIENT)
public final class StatsPanelKeybind {

    /** 按键分类的名称键，显示在「按键绑定」设置界面中。 */
    public static final String CATEGORY = "key.categories." + DamageModernization.MODID;

    /**
     * 打开属性面板的按键。
     *
     * <p>使用 {@link KeyConflictContext#IN_GAME}，因此只有在游戏内（而非打开菜单时）
     * 才会触发，避免与界面操作冲突。
     */
    public static final KeyMapping TOGGLE_PANEL = new KeyMapping(
            "key." + DamageModernization.MODID + ".toggle_stats_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            CATEGORY);

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
     * 每个客户端 tick 检查按键是否被按下，按下就打开面板界面。
     *
     * <p>使用 {@code consumeClick()} 而非 {@code isDown()}，
     * 这样按一次只触发一次，不会因为按住而反复打开。
     *
     * <p>只在「当前没有任何界面打开、且玩家已就绪」时打开，
     * 避免和背包、聊天、暂停菜单抢焦点。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE_PANEL.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && minecraft.player != null) {
                minecraft.setScreen(new StatsPanelScreen());
            }
        }
    }
}
