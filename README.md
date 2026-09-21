# Damage_Modernization

重构 Minecraft 底层的伤害构成。

原版伤害是一个固定的「攻击伤害」数值，直接参与扣血——攻击力、附魔、蓄力、暴击
全部被揉进同一个 `float`，无法分别调整。本 mod 把这套结构重写为
**四个互相独立的乘区**。

---

## 一、核心公式

```
最终伤害 = 攻击力区 × 伤害提升区 × 伤害倍率区 × 暴击伤害区
```

其中**攻击力区**自身是一次独立运算：

```
攻击力区 = 基础攻击力 × (1 + 攻击力百分比提升) + 固定攻击力
```

| 乘区 | 含义 | 内部运算方式 |
|---|---|---|
| 攻击力区 | 由基础攻击力叠加各类攻击力加成 | 加算（百分比作用于基础攻击力） |
| 伤害提升区 | 各类「增伤」效果 | **加算**：`1 + Σ增伤` |
| 伤害倍率区 | 独立乘数 | **乘算**：多个来源连乘 |
| 暴击区 | 暴击时的伤害倍率 | 暴击则取暴击倍率（下限 1.0），否则为 1.0 |

「伤害提升区」与「伤害倍率区」刻意区分：前者内部相加，避免多个小额加成相乘后数值爆炸；
后者提供真正的独立乘算，供稀有词条或大招使用。

---

## 二、基础攻击力：原版攻击伤害的重写

这是本 mod 最核心的设计，**不是把 `attack_damage` 改个显示名，而是重写其数值语义**。

### 原版的问题

原版 `generic.attack_damage` 的语义是**最终伤害**：

```
空手 1 + 铁剑 3 → 直接得到 4 点伤害
```

一旦读到这个值，加上蓄力与暴击就直接扣血。此时任何「攻击力 +10%」都只能作用在
最终伤害上，乘区模型形同虚设。

### 本 mod 的做法

`BaseAttackPowerConverter` 把 `attack_damage` 的数值语义完整迁移到 `base_attack_power`：

1. **基础值搬迁**：`attack_damage.baseValue → base_attack_power.baseValue`
2. **修饰符镜像**：把 `attack_damage` 上的修饰符（武器、装备、药水）
   以相同的数值与运算方式镜像到 `base_attack_power`

两步合起来保证 `base_attack_power` 的最终值与原版攻击伤害**完全一致**，
因此原版手感分毫不差：

| 状态 | 基础攻击力 |
|---|---|
| 空手 | 1.0 |
| 木剑 | 4.0 |
| 石剑 | 4.0 |
| 铁剑 | 5.0 |
| 钻石剑 | 6.0 |
| 下界合金剑 | 7.0 |

此后所有**百分比提升攻击力都以这个数值为基准**：

```
基础攻击力 4（石剑）
+ 攻击力 +50%  →  4 × 1.5 = 6.0
再 + 固定攻击力 2  →  6.0 + 2 = 8.0（进入攻击力区）
```

搬运的是 baseValue、镜像的是 modifier，二者互不重叠，
因此**不会把武器加成算两遍**。

> 原版 `attack_damage` 及其修饰符保留不动，以免破坏仍在读取它的第三方系统
> （生物 AI、其他模组的兼容逻辑）。本 mod 只是在其旁边维护一份语义标准的副本。

### 镜像的清理策略

镜像修饰符每轮都会**按前缀整体清除后重建**，而不是依赖「记住上一轮加了哪些」。

早期实现把已添加的镜像 id 记在一个按实体 UUID 索引的静态表里，下次据此删除。
这种「靠记忆」的做法在换维度、死亡重生、以及客户端/服务端各自维护一份表的情况下
会失配，导致**旧武器的加成残留在空手上**。现在改为按固定的 id 前缀扫描清除，
不再依赖任何跨调用的状态，因此换手/重生/两端不同步都不会残留。

此外，装备变化时会立即刷新一次镜像，属性面板无需等到下一次攻击就能反映当前武器。

### 武器 tooltip 显示为「基础攻击力」

原版物品通过 `generic.attack_damage` 的修饰符表达武器加值，tooltip 里写作「攻击伤害」。
本 mod 把这部分数值重写成了**基础攻击力**，字样与实际语义不符，
因此武器 tooltip 上那一行会被**就地替换**为：

```
基础攻击力   7
```

- **数值不变**：沿用原版那一行的数值（`武器修饰符 + 玩家自身基础值`），
  所以钻石剑依然是 `7`，只是属性名称变成了基础攻击力。
- **占据原行位置**：同一个数值不会出现两行，避免重复与歧义。
- **颜色沿用原版**：与其余属性行一致，使用深绿（`DARK_GREEN`）。

> 实现上通过 `ItemTooltipEvent` 定位并替换原版那一行。
> 由于原版该行的结构是 `literal(" ").append(translatable)`，
> 可翻译内容位于**兄弟节点**而非顶层，定位时必须递归查找；
> 数值则从该行文本解析（`" 7 Attack Damage"` → `7`），
> 因为原版显示的是含实体基础值的总和，无法从物品自身直接取得。
> 找不到该行或解析失败时不做任何改动，避免误删其他模组的内容或显示空白。

---

## 三、属性列表

| 属性 ID | 默认值 | 说明 |
|---|---|---|
| `damagemodernization:base_attack_power` | 1.0 | 基础攻击力，一切攻击力计算的基准 |
| `damagemodernization:attack_power_percent` | 0% | 攻击力百分比提升，作用于基础攻击力 |
| `damagemodernization:attack_power_flat` | 0.0 | 固定攻击力加值 |
| `damagemodernization:damage_amplifier` | 0% | 伤害提升（加算区） |
| `damagemodernization:damage_multiplier` | 1.0 | 伤害倍率（乘算区） |
| `damagemodernization:crit_chance` | 5% | 暴击率 |
| `damagemodernization:crit_damage` | 1.5 | 暴击伤害倍率 |

全部属性在 mod 启动时**注入到所有生物类型**，因此「所有生物的所有伤害」
都能走四乘区管线。注入使用属性默认值，不会改变任何生物的现有强度。

---

## 四、暴击接管

原版在 `Player.attack()` 中已经把「蓄力系数」与「暴击 ×1.5」乘进伤害，
再交给 `hurt()`。如果不处理，我们的暴击区会与原版暴击**叠加成双重暴击**。

因此本 mod 在 `CriticalHitEvent` 中：

- 把原版暴击倍率压回 `1.0`，取消其乘算；
- 改由 `crit_chance` 属性掷骰决定是否暴击；
- 记录**尚未被蓄力与暴击乘算**的原始基础攻击力，供伤害事件重建。

这样「跳跃下劈必定暴击」被替换为概率暴击，且不会双重计算。

### 暴击伤害的负数加成与下限

`crit_damage` 属性与 `critZone.globalDamageBonus` 配置**都允许为负**，
用于表达「降低暴击伤害」的减益效果。但暴击乘区有**硬下限 1.0**：

```
critZone = 暴击 ? max(1.0, 暴击伤害) : 1.0
```

因此无论暴击伤害被削减到多低，**暴击永远不会比不暴击造成更低的伤害**。
例：暴击伤害 `1.5`，吃一个 `-1.0` 的减益 → 存储值 `0.5` → 实际生效 `1.0`（暴击无额外收益）。

> 存储值与生效值是分开的：`DamageContext.critDamage()` 返回原始存储值（可能低于 1.0），
> 下限钳制发生在 `DamagePipeline.computeZones()`。这样其他 mod 仍能读到真实的暴击伤害数值
> 用于界面显示或进一步计算。

---

## 五、给其他 mod 的接口

四个乘区全部是**可插拔实现**，内置乘区与第三方乘区走完全相同的执行路径。

### 5.1 注册长期乘区

适合装备词条、天赋、职业系统等长期生效的规则，性能最好。

```java
public final class FireDamageZone implements IDamageZone {
    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "fire_bonus");
    }

    @Override
    public int priority() {
        return ZonePriorities.DEFAULT;
    }

    @Override
    public void apply(DamageContext ctx) {
        if (ctx.victim().isOnFire()) {
            ctx.multiplyDamageMultiplier(1.2D); // 放入乘算区
        }
    }
}

// 注册
DamageZoneRegistry.register(new FireDamageZone());
```

也可以用 lambda 快速注册：

```java
DamagePipeline.registerSimple(
        ResourceLocation.fromNamespaceAndPath("mymod", "low_health"),
        ZonePriorities.DEFAULT,
        ctx -> {
            if (ctx.hasAttacker() && ctx.attacker().getHealth() < 4.0F) {
                ctx.addDamageAmplifier(0.3D); // 加算区 +30%
            }
        });
```

### 5.2 监听单次伤害

适合按次判断的状态联动：

```java
@SubscribeEvent
public static void onDamageZone(DamageZoneEvent event) {
    DamageContext ctx = event.getContext();
    if (ctx.victim().isInWater()) {
        ctx.addDamageAmplifier(0.5D); // 加算区 +50%
    }
}
```

### 5.3 直接计算（不造成真实伤害）

用于显示预估伤害或单元测试：

```java
double expected = DamagePipeline.composeMelee(
        source, attacker, victim, baseAttackPower, crit);
```

### 5.4 完全接管

```java
ctx.overrideFinalDamage(42.0D); // 跳过乘区运算
```

### 5.5 API 一览

| 类型 | 作用 |
|---|---|
| `IDamageZone` | 乘区接口 |
| `DamageContext` | 伤害上下文，可读写各乘区 |
| `DamageZoneRegistry` | 乘区注册表 |
| `DamageZoneEvent` | 每次伤害前触发的事件 |
| `DamagePipeline` | 合成入口 |
| `ZonePriorities` | 内置优先级常量 |

`DamageContext` 的修改方法遵循乘区语义：
`addAttackPowerPercent` / `addAttackPowerFlat` / `addDamageAmplifier` 是**累加**，
`multiplyDamageMultiplier` 是**连乘**，与公式中的加算/乘算区一一对应。

任何乘区抛出异常都会被管线捕获并记录，**不会**让伤害归零——
一个第三方 mod 的 bug 不应该瘫痪整个伤害系统。

---

## 六、属性面板（GUI）

游戏内按 **K**（可在「选项 → 按键绑定 → 伤害现代化」中修改）打开属性面板。

```
                    伤害属性
──────────────────────────────
攻击力                 6（1+5）
伤害提升            25%（0%+25%）
伤害倍率          100%（100%+0%）
暴击率                15%（15%+0%）
暴击伤害          150%（150%+0%）
```

### 攻击力区：合并为一行，提升值显示换算后的点数

**攻击力区**由三个属性共同决定：`基础攻击力`、`攻击力百分比提升`、`固定攻击力`。
三者同属一个乘区，因此**合并为一行**展示：

```
攻击力区 = 基础攻击力 × (1 + 百分比提升) + 固定攻击力
```

其中**提升值以点数显示**，而不是百分比本身——百分比是「率」，
玩家真正关心的是它贡献了多少点攻击力：

```
提升值 = 总值 − 基础值 = 基础攻击力 × 百分比提升 + 固定攻击力
```

因为武器加成以修饰符形式存在，`基础值` 是不含加成的基准，
所以显示恒满足 `总值 = 基础值 + 提升值`。

示例（基础值 1，钻石剑 +3，攻击力 +50%）：
`攻击力 6（1+5）` —— 其中 5 = 3（武器）+ 2（百分比换算出的点数）。

### 其余乘区：总值（基础值+提升值）

伤害提升、伤害倍率、暴击率、暴击伤害各自只对应**一个**属性，
不存在跨属性合并的问题，因此直接沿用属性自身的显示格式，
百分比类属性自动带 `%`，与物品 tooltip 中的显示**完全一致**。

### 其他

面板默认关闭，只在按键后显示，不干扰正常游玩。
负的提升值会去掉格式化器自带的负号，避免出现 `+-` 这样的双符号。

---

## 七、配置

所有乘区的**默认数值与开关**都在配置文件中暴露，模组包作者无需写代码即可调参。
配置文件位于 `config/damagemodernization-common.toml`。

### 总开关

| 键 | 默认 | 说明 |
|---|---|---|
| `enableFourZoneModel` | true | 关闭后完全回到原版伤害行为 |
| `applyZonesToEnvironmentalDamage` | true | 环境伤害是否也走乘区 |

### 攻击力区

| 键 | 默认 | 说明 |
|---|---|---|
| `attackPowerZone.enabled` | true | 启用开关 |
| `attackPowerZone.scale` | 1.0 | 全局缩放，作用于整个攻击力区 |
| `attackPowerZone.formula` | `FULL` | `FULL` / `FLAT_ONLY` / `PERCENT_ONLY` |

### 伤害提升区（加算）

| 键 | 默认 | 说明 |
|---|---|---|
| `damageAmplifierZone.enabled` | true | 启用开关 |
| `damageAmplifierZone.globalBonus` | 0.0 | 全局加成，0.25 = 所有伤害 +25% |

### 伤害倍率区（乘算）

| 键 | 默认 | 说明 |
|---|---|---|
| `damageMultiplierZone.enabled` | true | 启用开关 |
| `damageMultiplierZone.globalFactor` | 1.0 | 全局系数，1.5 = 所有伤害 ×1.5 |

### 暴击区

| 键 | 默认 | 说明 |
|---|---|---|
| `critZone.enabled` | true | 关闭后不产生暴击 |
| `critZone.globalDamageBonus` | 0.0 | 全局暴击伤害加成（加法叠加，可为负，生效下限 1.0） |

### 属性默认值

| 键 | 默认 | 说明 |
|---|---|---|
| `defaults.critChance` | 0.05 | 默认暴击率 |
| `defaults.critDamage` | 1.5 | 默认暴击伤害（可设 1.0 以下，生效下限 1.0） |
| `defaults.damageMultiplier` | 1.0 | 默认伤害倍率 |

### 调试

| 键 | 默认 | 说明 |
|---|---|---|
| `debug.logZoneCalculation` | false | 打印每次伤害的四乘区明细 |
| `debug.logZoneRegistration` | true | 启动时打印已注册乘区列表 |

---

## 七、兼容性说明

- **原版 `attack_damage` 未被移除**，仍可供其他系统读取。
- **护甲、附魔、抗性、盾牌、吸收全部保持原版行为**：
  改造发生在 `LivingIncomingDamageEvent`，位于所有减免计算**之前**，
  因此我们改的是「伤害的构成」，而不是在减免后粗暴覆盖。
- **投射物伤害（箭、火球）不应用攻击力属性**，因为它们是直接实体，
  拥有独立的基础伤害；只有生物直接近战攻击才读取攻击力属性。
- 内置乘区可以被注销替换：`BuiltInZones.unregisterAll()`，
  然后用自定义实现注册同名乘区。

---

## 八、源码导航

```
com.vestudio.dmmod
├── DamageModernization           mod 入口，属性注册与生物属性注入
├── Config                        全部配置项
├── api
│   ├── DMAttributes              七个属性的定义
│   └── zone
│       ├── IDamageZone           乘区扩展接口
│       ├── DamageContext         伤害上下文
│       ├── DamageZoneRegistry    乘区注册表
│       ├── DamageZoneEvent       单次伤害事件
│       ├── DamagePipeline        合成入口与公式本体
│       └── ZonePriorities        优先级常量
└── damage
    ├── BaseAttackPowerConverter  攻击伤害 → 基础攻击力的重写
    ├── AttackContext             攻击上下文传递
    ├── DamageEventHandler        接管原版伤害管线
    └── zone
        ├── AttackPowerZone       内置：攻击力区
        ├── DamageAmplifierZone   内置：伤害提升区
        ├── DamageMultiplierZone  内置：伤害倍率区
        ├── CritZone              内置：暴击区
        └── BuiltInZones          内置乘区注册入口

com.vestudio.dmmod.client（仅客户端）
├── StatsPanelKeybind             按键绑定与面板显隐
└── StatsPanelOverlay             属性面板渲染
```
