# Damage_Modernization

重构 Minecraft 底层的伤害构成。

原版伤害是一个固定的「攻击伤害」数值，直接参与扣血——攻击力、附魔、蓄力、暴击
全部被揉进同一个 `float`，无法分别调整。本 mod 把这套结构重写为
**四个互相独立的乘区**。

---

## 一、核心公式

```
最终伤害 = 攻击力区 × 伤害倍率区 × 暴击区 × 增减伤区
```

其中**攻击力区**自身是一次独立运算：

```
攻击力区 = 基础攻击力 × (1 + 攻击力百分比提升) + 固定攻击力
```

| 乘区 | 含义 | 内部运算方式 |
|---|---|---|
| 攻击力区 | 由基础攻击力叠加各类攻击力加成 | 加算（百分比作用于基础攻击力） |
| 伤害倍率区 | 独立乘数 | **乘算**：多个来源连乘 |
| 暴击区 | 暴击时的伤害倍率 | 暴击则取暴击倍率（下限 1.0），否则为 1.0 |
| **增减伤区** | 攻击方的增伤与受害方的减伤 | **加算**：`Σ增伤 − Σ减伤`，再经曲线换算 |

### 增减伤区：增伤与减伤是同一乘区的两种体现

这是本 mod 数值设计的核心之一：**增伤和减伤不是两个相乘的乘区，而是同一个加算区里的加减**。

```
Σ = Σ(攻击方各类增伤) − Σ(受害方各类减伤)
增减伤区 = amplifier(Σ)
```

因此「+50% 增伤」遇到「40% 减免」的结果是 `Σ = 0.1`（而非 `1.5 × 0.6`），
增伤与减伤会**相互抵消**。这也是为什么该乘区位于**承伤侧**——
只有在那里才能同时读到攻守双方的贡献。

#### 换算曲线：前 50% 线性，超出部分对数

```
Σ ≥ -0.5   →  1 + Σ                          线性
Σ < -0.5   →  0.5 / (1 + k·(|Σ| - 0.5))      对数式衰减（k 可配，默认 2.0）
```

**为什么前 50% 线性**：让面板上的减免比例与实际效果一致——减免 25% 就承伤 0.75。

**为什么超出后改用对数**：若继续线性，Σ 到 -1 时乘数为 0（完全免疫），
再堆还会变成负数（反向治疗）。对数曲线则**恒大于 0**，越堆越接近但永远到不了：

| Σ | 线性 `1+Σ` | 本曲线（k=2） |
|---|---|---|
| -0.25 | 0.75 | **0.75**（线性段） |
| -0.5 | 0.5 | **0.5**（分界，连续） |
| -1.0 | **0**（免疫） | **0.25** |
| -2.0 | **-1**（负伤害） | **0.125** |
| -5.0 | -4 | **0.05** |

两段在 Σ=-0.5 处都取 0.5，曲线连续无跳变。系数 `k` 可在配置中调整
（`damageAmplifierZone.reductionCurveCoefficient`）。

「伤害倍率区」与「增减伤区」刻意区分：前者是纯乘算，供独立乘数（稀有词条、大招）使用；
后者内部相加，避免多个小额加成相乘后数值爆炸。

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

### 武器 tooltip 显示「增加的基础攻击力」

原版物品通过 `generic.attack_damage` 的修饰符表达武器加值，tooltip 里写作「攻击伤害」。
本 mod 把这部分数值重写成了**基础攻击力**，字样与实际语义不符，
因此武器 tooltip 上那一行会被**就地替换**为：

```
+6 基础攻击力
```

- **显示的是「增加量」**：即武器自身的攻击力修饰符数值，**不含**玩家自身的基础值。
  钻石剑显示 `+6`，含义是「装备后基础攻击力 +6」，而不是装备后的总和。
- **占据原行位置**：同一个数值不会出现两行，避免重复与歧义。
- **颜色沿用原版**：与原版属性行一致的深绿（`DARK_GREEN`）。

> 实现上通过 `ItemTooltipEvent` 定位并替换原版那一行。
> 由于原版该行的结构是 `literal(" ").append(translatable)`，
> 可翻译内容位于**兄弟节点**而非顶层，定位时必须递归查找；
> 数值则直接读取物品自身的攻击力修饰符。
> 找不到该行或没有攻击力修饰符时不做任何改动，避免误删其他模组的内容。

---

## 三、属性列表

| 属性 ID | 默认值 | 说明 |
|---|---|---|
| `damagemodernization:base_attack_power` | 1.0 | 基础攻击力，一切攻击力计算的基准 |
| `damagemodernization:attack_power_percent` | 0% | 攻击力百分比提升，作用于基础攻击力 |
| `damagemodernization:attack_power_flat` | 0.0 | 固定攻击力加值 |
| `damagemodernization:damage_amplifier` | 0% | 通用增伤（增减伤区的子项） |
| `damagemodernization:damage_multiplier` | 1.0 | 伤害倍率（乘算区） |
| `damagemodernization:crit_chance` | 5% | 暴击率 |
| `damagemodernization:crit_damage` | 1.5 | 暴击伤害倍率 |
| `damagemodernization:base_health` | 原版血量 | 基础生命值，含装备等加成 |
| `damagemodernization:health_percent` | 0% | 生命值百分比提升 |
| `damagemodernization:health_flat` | 0.0 | 固定生命值 |
| `damagemodernization:physical_amplifier` | 0% | 物理伤害提升（增减伤区的子项） |
| `damagemodernization:magic_amplifier` | 0% | 魔法伤害提升（增减伤区的子项） |
| `damagemodernization:physical_resistance` | 0% | 物理伤害减免（承伤乘区的子项） |

全部属性在 mod 启动时**注入到所有生物类型**，因此「所有生物的所有伤害」
都能走四乘区管线。注入使用属性默认值，不会改变任何生物的现有强度。

> `base_health` 的默认值按**生物类型**取原版血量（僵尸 20、鸡 4……），
> 由数据文件的 `vanillaSource` 声明，不是全局固定值。

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
攻击力             10.5（7 + 3.5）
伤害提升                     0%
伤害倍率                   125%（25%）
暴击率                     15%（10%）
暴击伤害                 150%
```

### 攻击力区：结果（基础攻击力 + 非基础攻击力）

**攻击力区**由三个属性共同决定：`基础攻击力`、`攻击力百分比提升`、`固定攻击力`。
三者同属一个乘区，因此**合并为一行**，并且是该乘区中唯一有基础部分的一项：

```
攻击力区 = 基础攻击力 × (1 + 百分比提升) + 固定攻击力
非基础攻击力 = 结果 − 基础攻击力
```

**加号前面是基础攻击力，且它包含武器贡献**——武器的攻击伤害会被换算并计入
基础攻击力（空手 1、钻石剑 7），因此**不是固定为 1**：

| 状态 | 基础攻击力 |
|---|---|
| 空手 | 1 |
| 钻石剑 | 7 |
| 下界合金剑 | 8 |

加号**后面**是**非基础**的那部分，即百分比提升与固定攻击力额外带来的点数。

示例（钻石剑、攻击力 +50%）：

```
10.5（7 + 3.5）      ← 7 是基础攻击力（含钻石剑），3.5 是非基础部分
```

没有非基础部分时省略括号，只显示结果：

```
7                    ← 钻石剑无任何攻击力加成
```

### 其余乘区：结果（增加量）

伤害提升、伤害倍率、暴击率、暴击伤害没有分开的基础值，
因此**不显示基础值、也不使用 `+` 号**，只显示「结果（增加量）」：

```
伤害倍率   125%（25%）
暴击率      15%（10%）
```

增加量以百分比呈现；**数值没有变动时省略括号**，只显示结果：

```
伤害提升   0%        ← 无加成，不显示括号
暴击伤害   150%      ← 等于默认值，不显示括号
```

### 刷新时机

基础攻击力由服务端**每秒（20 tick）**权威刷新一次，
再经原有的属性同步下发到客户端。因此换装后面板最迟 1 秒内就会更新，
不需要先打一下目标。1 秒的间隔足以覆盖玩家的换装频率，
又避免了每 tick 增删修饰符的无谓开销与频繁网络同步。

### 渲染缓存

GUI 每帧都会重绘，但数值最多每秒才变一次。若每帧都重新读取属性、
重新格式化文本、重新测量宽度，绝大多数帧都是白做的开销。

因此面板带一层缓存：先取出构成面板的**原始数值**做比较，
与上次完全一致时直接复用已排版好的结果，只有确实变化才重建。
比较本身每 5 帧才做一次（60 FPS 下延迟不足 0.1 秒，肉眼不可察）。

实测（约 2900 帧、期间多次切换手持物品）：**重建 51 次、复用 2851 次，复用率 98.2%**，
且每次重建都对应真实的数值变化，没有多余重建。

### 其他

面板默认关闭，只在按键后显示，不干扰正常游玩。

---

## 七、数据驱动：属性、乘区与公式

属性定义、乘区参数与**计算公式**都存放在数据文件中，改数值或改算法都无需重新编译。

### 存放位置

```
config/damagemodernization/*.json
```

首次运行会自动释放一份带完整注释的默认文件作为改写起点，**且绝不覆盖已有文件**。
目录下所有 `.json` 都会读取并按文件名顺序合并；同 id 后加载者覆盖前者。

### 乘区是通用的公式单元

「乘区」并不专指伤害。任何「多个可配置公式协同算出一个结果」的地方都可以有自己的乘区集合，
由 `scope` 字段区分属于哪套体系：

| scope | 体系 | 乘区 | 组合方式 |
|---|---|---|---|
| `damage` | 伤害公式（攻方：这一下打得多疼） | 攻击力区、伤害倍率区、暴击区 | 全部**连乘** |
| `taken` | 承伤公式（守方 + 增减伤） | 增减伤区（内含攻守双方贡献） | 乘数叠在伤害结果之上 |
| `health` | 生命值 | 基础生命值缩放、生命值提升 | 两段依次代入 |

「增减伤区」为何在 `taken` 而非 `damage`：它需要**同时**读到攻击者的增伤属性
与受害者的减伤属性，才能把两者相加成一个 Σ。放在承伤侧是唯一能同时访问双方的位置。

三套体系**各自独立、互不参与**：

- 生命值乘区不会进入伤害的连乘链
- **承伤乘区只作用在伤害结果之上，不会回头改写攻击方的任何乘区**

最后一条尤其重要：它保证了攻守双方互不污染。最终伤害为：

```
最终伤害 = （伤害公式的四个乘区连乘） × 承伤公式的乘数
```

### 伤害类型：标签集合，可同时成立

「什么算物理伤害」由**排除名单**决定，而不是白名单：

```
data/damagemodernization/tags/damage_type/non_physical.json   （排除式：不在名单内即算物理）
data/damagemodernization/tags/damage_type/magic.json          （包含式：登记了才算魔法）
data/damagemodernization/tags/damage_type/fire.json           （包含式）
```

**伤害类型是「标签集合」而不是互斥的单选**——一次伤害可以同时带有多种类型。

这一点是刻意设计的：若把类型建模成枚举，「某装备让物理伤害同时视为魔法」
就只能表达成「不再是物理」，于是**物理增伤会失效**，与期望不符。

当前归类（基于原版 47 个伤害类型）：

| 类型 | 判定方式 | 包含 |
|---|---|---|
| **物理** | 排除式 | 近战、弓箭与投掷物、火球类等（不在排除名单内的全部） |
| **魔法** | 包含式 | `magic`、`indirect_magic`、`dragon_breath`、`sonic_boom`、`wither`、`wither_skull` |
| **火焰** | 包含式 | `in_fire`、`on_fire`、`campfire`、`lava`、`hot_floor`、`fireball` 等 |

> **原版的药水与状态效果伤害属于魔法类型**（`magic`、`indirect_magic`）。
>
> **恶魂火球**同时是物理与火焰；**凋灵之首**只算魔法，不算物理。

新增伤害类型默认归入物理——除非把它加进排除名单。

### 各类型增伤：增减伤区里的子项

每个类型对应一个增伤属性，作为**增减伤区内部的子项**。
注意该乘区位于 `taken` 作用域——因为它要同时拿到攻守双方的贡献：

```
amplifier(
    damage_amplifier               ← 攻击者的通用增伤
  + amplifier_bonus                ← 全局配置加成
  + (has_physical ? physical_amplifier : 0)   ← 攻击者的物理增伤
  + (has_magic    ? magic_amplifier    : 0)   ← 攻击者的魔法增伤
  - (has_physical ? physical_resistance : 0)  ← 受害者的物理减伤
)
```

因为是同一个加算区，各类型增伤与通用增伤**相加**而非相乘；
且由于伤害类型是集合，**同时成立时会一并生效**：

| 场景 | 物理+50%、魔法+30% | Σ | 结果 |
|---|---|---|---|
| 普通物理攻击 | 只吃物理 | +0.5 | `1.5` |
| 物理伤害「同时视为魔法」 | 两者都吃 | +0.8 | **`1.8`** |
| 纯魔法伤害（药水） | 只吃魔法 | +0.3 | `1.3` |

### 增伤与减伤的相互抵消

由于减伤也进入同一个 Σ（取负），两者会直接抵消：

| 攻击者增伤 | 受害者减免 | Σ | 增减伤区 |
|---|---|---|---|
| +50% | 40% | +0.1 | `1.1` |
| +20% | 40% | -0.2 | `0.8`（线性段） |
| +40% | 40% | 0 | `1.0`（完全抵消） |
| 0 | 50% | -0.5 | `0.5`（分界） |
| 0 | 100% | -1.0 | `0.25`（曲线段） |

### 给其他 mod 的接口：追加伤害类型

`DamageTypeContributor` 可在标签判定的基础上**追加**类型：

```java
DamageTypeRegistry.registerContributor((source, victim, types) ->
        types.has(DamageTypeSet.PHYSICAL) && hasArcaneWeapon(source)
                ? types.with(DamageTypeSet.MAGIC)   // 追加，不是替换
                : null);
```

关键语义是 **`with()` 追加而非替换**——原类型保留，
因此物理增伤与魔法增伤会**同时生效**，这正是「同时视为」所期望的行为。

也提供便捷构造：

```java
DamageTypeRegistry.registerContributor(
        DamageTypeContributor.when((source, victim) -> 某条件, DamageTypeSet.MAGIC));
```

贡献者出错会被隔离（记录日志并跳过），不影响伤害结算。

新增一种伤害类型时，通常只需：

1. 加一个 `damage_type/<名字>.json` 标签
2. 在 `BuiltInDamageTypes` 里注册（包含式或排除式）
3. 加一个对应的 `xxx_amplifier` 属性
4. 在增减伤区（`taken/amplifier`）的公式里加一个子项

其中第 4 步改的是数据文件里的公式文本，**不需要改 Java**。

### 数据文件结构

```json
{
  "attributes": [
    {
      "id": "damagemodernization:base_attack_power",
      "default": 1.0,
      "min": 0.0, "max": 1000000.0,
      "display": "plain",
      "syncable": true
    }
  ],
  "zones": [
    {
      "scope": "damage",
      "id": "attack_power",
      "priority": -500,
      "scale": 1.0,
      "formula": "base_attack_power * (1 + attack_power_percent) + attack_power_flat",
      "requires": ["base_attack_power", "attack_power_percent", "attack_power_flat"]
    }
  ]
}
```

**属性字段**

| 字段 | 说明 |
|---|---|
| `id` | 属性标识 |
| `default` | 固定默认值；与 `vanillaSource` 二选一 |
| `vanillaSource` | 默认值取自原版属性（按生物类型），如 `minecraft:max_health` |
| `min` / `max` | 取值范围 |
| `display` | `plain` 直接显示，`percent` 显示成百分比 |
| `sentiment` | `positive` / `negative` / `neutral`，影响 tooltip 配色 |
| `syncable` | 是否同步到客户端 |

**乘区字段**

| 字段 | 说明 |
|---|---|
| `scope` | 所属体系：`damage` / `health` |
| `id` | 乘区路径 |
| `enabled` | 是否启用 |
| `priority` | 执行顺序，越小越先执行 |
| `scale` | 全局缩放，作用在公式输出之上 |
| `formula` | 公式文本 |
| `requires` | **变量白名单**，公式只能引用这里声明的变量 |

### 公式语法

支持四则运算、括号、比较、逻辑、三元与常用函数：

```
base_attack_power * (1 + attack_power_percent) + attack_power_flat
is_critical ? max(1, crit_damage + crit_bonus) : 1
clamp(x, 0, 5)     min(a,b)     max(a,b)     abs(x)     floor(x)     ceil(x)
```

比较与逻辑运算结果为 `1.0` / `0.0`；除零返回 `0` 而非报错。

### 变量来源

公式可用的变量按以下顺序解析（后者可覆盖前者）：

| 来源 | 变量示例 | 说明 |
|---|---|---|
| 属性值 | `base_attack_power`、`health_percent` | 变量名即属性 ID 的路径部分 |
| 上下文 | `is_critical`、`raw_damage`、`is_environmental` | 当次结算的临时信息 |
| 全局系数 | `amplifier_bonus`、`multiplier_factor`、`crit_bonus`、`health_scale` | 由配置项提供 |

### 白名单校验

公式引用了未在 `requires` 中声明的变量，**该定义会在加载时被拒绝**并记录原因，
而不是运行期静默取到 0。这样拼写错误（如 `base_attck_power`）能立刻发现。

### 失败兜底

| 情况 | 行为 |
|---|---|
| 公式语法错误 / 变量未声明 | 拒绝该定义并记录原因 |
| 求值抛异常 / 结果非有限值 | 该乘区按中性值 `1.0` 处理，不影响其他乘区 |
| 数据文件缺失或全部无效 | 退回**内置硬编码公式**，mod 仍可正常工作 |

一份写错的数据文件不会让整个 mod 失效，也不会让伤害归零。

> **默认值对照**：数据文件里的默认公式与硬编码公式完全等价，
> 因此不装数据文件、或用默认文件，行为都保持不变。

---

## 八、配置

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

### 增减伤区（加算）

| 键 | 默认 | 说明 |
|---|---|---|
| `damageAmplifierZone.enabled` | true | 启用开关 |
| `damageAmplifierZone.globalBonus` | 0.0 | 全局加成，0.25 = 所有伤害 +25% |
| `damageAmplifierZone.reductionCurveCoefficient` | 2.0 | 减伤超出 50% 后对数曲线的陡峭程度 `k` |

`reductionCurveCoefficient` 的作用：

```
Σ ≥ -0.5  →  1 + Σ
Σ < -0.5  →  0.5 / (1 + k·(|Σ|-0.5))

k 越大  → 超出 50% 后衰减越快（堆减伤越不划算）
k 越小  → 衰减越慢（越接近但不等于免疫）
k = 0   → 超出部分恒为 0.5，不再衰减
```

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

### 生命值

| 键 | 默认 | 说明 |
|---|---|---|
| `health.baseScale` | 1.0 | 基础生命值的全局缩放，1.5 即所有生物血量 +50% |

`health.baseScale` 是**独立的缩放乘区**，与「生命值百分比提升」互不影响：

```
基础生命值 = （原版血量 + 装备等加成） × health.baseScale
最终生命值 = 基础生命值 × (1 + 生命值百分比) + 固定生命值
```

### 调试

| 键 | 默认 | 说明 |
|---|---|---|
| `debug.logZoneCalculation` | false | 打印每次伤害的四乘区明细 |
| `debug.logZoneRegistration` | true | 启动时打印已注册乘区列表 |

---

## 九、兼容性说明

- **原版 `attack_damage` 未被移除**，仍可供其他系统读取。
- **护甲、附魔、抗性、盾牌、吸收全部保持原版行为**：
  改造发生在 `LivingIncomingDamageEvent`，位于所有减免计算**之前**，
  因此我们改的是「伤害的构成」，而不是在减免后粗暴覆盖。

  代码中**从未出现** `ARMOR`、`getDamageAfterArmorAbsorb` 或 `CombatRules`——
  护甲公式、属性、执行时机都未被触碰。

  实测（以真实生物为受害者，验证「我们的构成 × 原版护甲」）：

  | 护甲 | 构成值 | 实际扣血 | 原版公式理论值 |
  |---|---|---|---|
  | 0 | 1.0 | 1.0000 | 1.0000 |
  | 5 | 1.0 | 0.8200 | 0.8200 |
  | 10 | 1.0 | 0.6200 | 0.6200 |
  | 20 | 1.0 | 0.2200 | 0.2200 |

  每组的实际扣血都精确等于「构成值经原版护甲减免」，说明护甲链路未被干扰。
  需要留意的是：**护甲的输入值会变**（这正是改伤害构成的必然结果），
  护甲按比例减免时最终扣血量随之变化。
- **投射物伤害（箭、火球）不应用攻击力属性**，因为它们是直接实体，
  拥有独立的基础伤害；只有生物直接近战攻击才读取攻击力属性。
- 内置乘区可以被注销替换：`BuiltInZones.unregisterAll()`，
  然后用自定义实现注册同名乘区。

---

## 十、源码导航

```
com.vestudio.dmmod
├── DamageModernization           mod 入口，属性注册、属性注入与数据加载
├── Config                        全部配置项
├── api
│   ├── DMAttributes              十个属性的定义与公式变量名映射
│   ├── PercentDisplayAttribute   百分比显示属性
│   └── zone
│       ├── IDamageZone           乘区扩展接口
│       ├── DamageContext         伤害上下文
│       ├── DamageZoneRegistry    乘区注册表
│       ├── DamageZoneEvent       单次伤害事件
│       ├── DamagePipeline        合成入口（数据驱动优先，内置公式兜底）
│       └── ZonePriorities        优先级常量
├── formula                       数据驱动的核心
│   ├── FormulaEngine             公式解析与求值（含 amplifier 增减伤换算）
│   ├── ZoneScope                 乘区所属体系（damage / taken / health）
│   ├── ZoneDefinition            乘区定义（含白名单校验）
│   ├── AttributeDefinition       属性定义（含校验）
│   ├── ModData                   数据文件根结构
│   ├── DataRepository            数据文件加载与仓库
│   └── ZoneEvaluator             按作用域求值乘区，管理变量注入
└── damage
    ├── ZoneIds                   内置乘区 id 常量
    ├── AttributeMirror           属性镜像工具（基础值 + 修饰符）
    ├── BaseAttackPowerConverter  攻击伤害 → 基础攻击力的重写
    ├── HealthFormulaEvaluator    生命值乘区求值（主路径，数据驱动）
    ├── DamageFormulaEvaluator    伤害乘区求值（主路径，数据驱动）
    ├── TakenFormulaEvaluator     增减伤乘区求值（含攻守双方贡献）
    ├── BuiltInDamageTypes        内置伤害类型注册（物理/魔法/火焰）
    ├── HealthCalculator          生命值计算（**兜底**：数据缺失时启用）
    ├── AttackContext             攻击上下文传递
    ├── DamageEventHandler        接管原版伤害管线与每秒刷新
    └── zone
        ├── AttackPowerZone       旧的乘区实现（已由数据驱动取代，保留兼容）
        ├── DamageAmplifierZone   同上
        ├── DamageMultiplierZone  同上
        ├── CritZone              同上
        └── BuiltInZones          内置乘区注册入口

com.vestudio.dmmod.api.damagetype（伤害类型 API）
├── DamageTypeSet                 类型集合（可同时成立，with() 为追加）
├── DamageTypeRegistry            标签映射与贡献者注册
└── DamageTypeContributor         供其他 mod 追加类型

com.vestudio.dmmod.client（仅客户端）
├── StatsPanelKeybind             按键绑定与面板显隐
├── StatsPanelOverlay             属性面板渲染
└── AttackPowerTooltip            武器 tooltip 改造

com.vestudio.dmmod.util
├── TooltipLines                  tooltip 行定位
└── StatFormat                    面板排版规则（可独立测试）
```

> `damage/zone` 下的四个旧乘区实现与 `HealthCalculator` 现在都是**兜底路径**：
> 数据文件缺失或被全部拒绝时才会启用，保证 mod 不会因为数据问题而失效。
