# GTO Issue 草稿（Bug Report / 错误报告）

提交網址：https://github.com/GregTech-Odyssey/GregTech-Odyssey/issues/new?template=1-report-bug.yml

---

## 標題 / Title

```
ME超算核心：无论分裂出多少颗 CPU，对 AE2 永远只暴露一颗空闲，整张网络每 tick 只能开出一张合成单
```

---

## GTO Pack Version / GTO 包版本

```
0.5.6-beta（gtocore-forge-1.20.1-0.5.6-beta.jar）
```

> 若啟動器顯示帶 commit 短碼的版本（如 `0.5.6-beta-72c46c`），請改填完整那串。

---

## Priority / 优先级

```
Major / 重要
```

> 理由：这是自动化吞吐的硬上限。玩家投入资源把超算核心分裂成数百颗 CPU，实际并发量仍是 1；
> 且请求器之间的胜负由 HashSet 遍历顺序决定，会有物品被结构性饿死、永远轮不到。

---

## Play Environment / 游戏环境

```
Single Player / 单人游戏
```

---

## Expected Behavior / 预期行为

```
ME超算核心分裂出 N 颗合成 CPU 之后，AE2 应该能同时为多个自动化请求分配 CPU：
同一 tick 内有 M 个请求器提交合成单时，只要还有空闲 CPU，就应该有 min(M, 空闲数) 张单开得出来。
```

---

## Observed Behavior / 实际行为

```
超算核心分裂出 256 颗 CPU（每颗可用 129,117,454,336 bytes），但：

1. 同一 tick 内不管有几个请求器提交，只有「第一个」拿得到 CPU，其余全部收到
   NO_SUITABLE_CPU_FOUND，要等下一次轮询（请求器约每 10 秒一次）再试。
2. 因此整张网络的合成开单速率被钉死在「每 tick 最多 1 张」。
3. 更严重的是：AE2 遍历用 HashSet，顺序在单次游戏执行中固定，所以排在后面的物品
   会「每一轮都排在后面」——不是概率性变慢，是结构性饿死，可以一次都轮不到。
```

---

## 决定性证据：AE2 自己的 `UnsuitableCpus` 回报

AE2 的 `CraftingService.submitJob` 选不到 CPU 时，回的不是单纯一个错误码，而是
`CraftingSubmitResult.noSuitableCpu(UnsuitableCpus)`，其中

```java
record UnsuitableCpus(int offline, int busy, int tooSmall, int excluded)
```

四个计数对应 `submitJob` 挑 CPU 的四道过滤（依序）：

```java
isActive() && !isBusy() && getAvailableStorage() >= bytes && canBeAutoSelectedFor(src)
```

把这个 detail 打出来之后，55 次 `NO_SUITABLE_CPU_FOUND` 的分布是：

```
32 次  UnsuitableCpus[offline=0, busy=2, tooSmall=0, excluded=0]
12 次  UnsuitableCpus[offline=0, busy=3, tooSmall=0, excluded=0]
 7 次  UnsuitableCpus[offline=0, busy=4, tooSmall=0, excluded=0]
 4 次  UnsuitableCpus[offline=0, busy=1, tooSmall=0, excluded=0]
```

**全部 55 次，`tooSmall` 和 `excluded` 都是 0**：

- `tooSmall=0` → 不是 CPU 容量不够（每颗可用 129,117,454,336 bytes）
- `excluded=0` → 不是 CPU 的 `CpuSelectionMode`（PLAYER_ONLY / MACHINE_ONLY）挡掉
- `offline=0` → 暴露出来的 CPU 都是活的
- `busy=1~4` → **AE2 看得见的 CPU 一共就只有 1~4 颗，而且每一颗都在忙**

超算核心分裂出来的是 **256 颗**。也就是说 AE2 的候选清单里从头到尾只有 1~4 颗，
其余 250 多颗它根本看不到。

### 与 gtolib 侧的交叉验证

同一场游戏，另一个方向（反射读 `CraftingInterfacePartMachine` 的两份清单）报出来的数字
和 AE2 的 `busy` 计数**完全一致**：

```
21:50:16  暴露 2 颗 / 共 256 颗   ← 同期 AE2 回报 busy=2
21:50:23  暴露 3 颗 / 共 256 颗   ← 同期 AE2 回报 busy=3
21:50:31  暴露 3 颗 / 共 256 颗
21:50:36  暴露 4 颗 / 共 256 颗   ← 同期 AE2 回报 busy=4
```

两个独立来源互相印证：**AE2 的候选清单 == part 的暴露清单，而暴露清单里没有空闲的**。

---

## 影响面（15 小时单人存档，逐条记录 submitJob 结果）

```
全网机器来源提交：成功 9,886 次 / 被弹回 8,944 次（NO_SUITABLE_CPU_FOUND 占 47.5%）

按 tick 统计（同一毫秒视为同一 tick）：
  同一 tick 有 >=2 个请求竞争的 tick 数： 5,803
    其中成功 1 张： 5,585  (96.2%)
    其中成功 2 张：    70  ( 1.2%)
    其中成功 0 张：   148
```

即使有 256 颗 CPU、每颗 129 GB bytes，只要同 tick 有人竞争，几乎必然只有一张单开得出来。

### 副作用：有物品会被结构性饿死

AE2 遍历 CPU / 请求器用的是 `HashSet`，顺序在单次游戏执行中固定。排在后面的物品
不是「概率性变慢」，而是**每一轮都排在后面**：

```
品项                              成功   弹回   成功率   最大 bytes
rare_earth_metal_dust              40     39     51%      12,812
tiny_gaia_dust                      0     79      0%      92,614
drilling_fluid                     22     25     47%     533,334   ← 大 6 倍反而过得去
dense_hydrazine_fuel_mixture       11     25     31%         125
liquid_oxygen                      11      1     92%       3,126
uranium_rod                         9      0    100%           2
```

`rare_earth_metal_dust` 和 `tiny_gaia_dust` 挂在**同一颗请求器**上，一个 51%、
一个 0/79。玩家手动下单则 13/13 全部成功（最大 995,215 bytes），因为手动的时机
不落在那个 10 秒轮询节拍上。

原始流水（同一毫秒送单，只有第一个成功）：

```
20:59:15.16  OK   rare_earth_metal_dust      12,812B
20:59:15.16  FAIL tiny_gaia_dust             92,614B   NO_SUITABLE_CPU_FOUND

20:59:45.16  OK   drilling_fluid            533,334B
20:59:45.16  FAIL rare_earth_metal_dust       6,404B   NO_SUITABLE_CPU_FOUND
20:59:45.16  FAIL tiny_gaia_dust             92,614B   NO_SUITABLE_CPU_FOUND
```

---

## 根因

真正持有 CPU 的是超算核心里的「合成CPU接口」part
`com.gtolib.api.machine.impl.part.CraftingInterfacePartMachine`，它有两份清单：

| 清单 | 内容 | 谁看得到 |
|---|---|---|
| 完整清单（`ObjectArrayList` 字段） | 分裂出来的全部 cluster，数量 2^(分裂层数)，可到数百颗 | 只有 part 自己 |
| 暴露清单（`getClusters()`） | **忙碌中的全部 ＋ 恰好一颗空闲** | AE2 的 `CraftingService` |

「只暴露一颗空闲」应该是刻意设计（否则 ME 终端会被几百颗 CPU 洗版），但它同时
**把并发开单数限制成了 1**：

1. AE2 `CraftingService.submitJob` 选 CPU 时只遍历暴露清单里那一颗空闲的；
2. 第一个请求把它占走 → 变忙碌 → 暴露清单里一颗空闲也没有；
3. 同一 tick 内后续所有 `submitJob` 一律 `NO_SUITABLE_CPU_FOUND`；
4. 要等 part 下一次周期 tick 重建暴露清单（`updateList()`，且只在「空闲数 != 1」时才重建），
   再 +1 tick 让 `CraftingService` 同步，才会有下一颗。

分析依据：GTOCore repo 早期提交 `592d908b`（多方块CPU）随库附的**未加密** `libs/gtolib-1.0.jar`。
现行 gtolib 已加密（方法变 `native`，实作在 `native0/native/*.bin`），只能由字段／方法签名与常量池
（仍引用 `isBusy`／`isDestroyed`／`setDestroyed`／`GridCraftingCpuChange`）判断设计未变。

---

## Suggested Resolution / 建议解决方案

```
一句话：暴露清单应该「按需供给」而不是「固定一颗」——有多少个请求同时要 CPU，
就暴露多少颗空闲，而不是让它们排队一 tick 抢一颗。

三种改法，由小到大：

============================================================
方案 A（最小改动）：把「恰好一颗空闲」改成「至少 K 颗空闲」
============================================================
把 updateList() 的重建条件从「空闲数 != 1」改成「空闲数 < K」，K 给个配置项
（默认 4 或 8）。ME 终端多显示几颗不至于洗版，但并发开单数立刻从 1 提到 K。

缺点：K 还是要玩家自己随请求器数量调，治标。

============================================================
方案 B（推荐）：按需补位
============================================================
在 CPU 被占走的当下（而不是等下一次周期 tick）立刻从完整清单补一颗空闲进暴露清单。
这样同一 tick 内 M 个请求会触发 M 次补位，各拿到自己的 CPU，不需要任何配置项，
玩家之后加多少请求器都自动跟上。

实作上只要让「暴露清单空闲数归零」这件事同步触发 updateList()，
而不是依赖 ConditionalSubscriptionHandler 的周期。

============================================================
方案 C（架构级，可参考 AdvancedAE）
============================================================
AdvancedAE 的量子计算机（Quantum Computer）面对同样的需求，采用的是
「单一 cluster 持有共用的储存＋协处理器池，每来一张单就动态生一个 CPU 实例」，
而不是预先分裂成 N 颗再配给；另外用一颗「Remaining Capacity CPU」显示剩余量，
所以 ME 终端也不会被洗版。

参考：https://github.com/pedroksl/AdvancedAE
      https://deepwiki.com/pedroksl/AdvancedAE/3.1-quantum-computer

这条改动最大，但从玩家角度最自然：投入多少资源就有多少并发，没有隐藏的
「同时只能开一张单」上限。
```

---

## 附带观察：这不是「CPU 消失」那个已知空窗

如果只把补位时机提前（即：暴露清单一空就立刻补一颗），能解掉「ME 终端短暂看不到 CPU」
的显示问题，但**解不掉本 issue**——因为补位后空闲数仍是 1，同一 tick 的第二个请求照样拿不到。
两者需要分开处理。

---

## Related Mod (Optional) / 相关模组（可选）

- [x] **GTOCore**
- [x] AE2
- [ ] GTM

---

## Final Checklist / 最终检查清单（三項都要勾，送出前自行確認）

- [ ] 我已搜索问题跟踪器，确认不存在类似报告。
- [ ] 我可以通过遵循上述步骤持续重现此问题，或无需重现。
- [ ] 我理解因整合包飞速地更新，非最新版本或极其相近的版本的错误极大可能已经修复。
