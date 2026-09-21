package com.vestudio.dmmod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端入口。
 *
 * <p>本 mod 的伤害逻辑完全运行在服务端，客户端侧只负责提供配置界面，
 * 因此这里保持极简。
 */
@Mod(value = DamageModernization.MODID, dist = Dist.CLIENT)
public class DamageModernizationClient {

    /**
     * 注册配置界面。
     *
     * @param container 当前 mod 容器
     */
    public DamageModernizationClient(ModContainer container) {
        // 让 NeoForge 自动为本 mod 的配置生成界面。
        // 配置项的中文/英文名称在 lang 文件中定义。
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
