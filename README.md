# NL_gto_cpu_fix

修 GTO 整合包（GregTech Odyssey）ME超算核心的一個時序問題：**分裂出來的 CPU 會短暫從 ME 網路上消失**，
期間 ME 終端的合成 CPU 列表看不到 CPU、新的合成請求也拿不到 CPU。

- MC 1.20.1 / Forge，單平台，GTO 整合包專用。
- modid `gto_cpu_fix`、package `com.gtocpufix`、log 前綴 `[cpufix]`。
- 不改 GTOCore、不動 gtolib：唯一的 mixin 掛在 AE2 的 `appeng.me.service.CraftingService`。

## 根因

ME超算核心（`me_cpu`）真正持有 CPU 的是裡面那個「合成CPU接口」part
（`com.gtolib.api.machine.impl.part.CraftingInterfacePartMachine`，gtolib 側）。它有兩份清單：

| 清單 | 內容 | 誰看得到 |
| --- | --- | --- |
| 完整清單（`ObjectArrayList` 欄位） | 分裂出來的全部 cluster，數量 2^(分裂層數)，可到數百顆 | 只有 part 自己 |
| 暴露清單（`getClusters()`） | **忙碌中的全部 ＋ 恰好一顆空閒** | AE2 的 `CraftingService` |

只暴露一顆空閒 CPU 是刻意設計（不然 ME 終端會被幾百顆 CPU 洗版）。問題在**補位的時機**：

1. 重建暴露清單的 `updateList()` 掛在 part 自己的週期 tick 上
   （舊版 `getOffsetTimer() % 10 == 0`，現行版改用 `ConditionalSubscriptionHandler` 的 cycle，可 `setCycle` 調整），
   而且只有「暴露清單裡空閒數 != 1」時才重建；
2. 那顆唯一暴露的空閒 CPU 一被下單佔用就變忙碌 → 暴露清單裡一顆空閒也沒有；
3. 要等下一次週期 tick 才補上下一顆，再 +1 tick 讓 `CraftingService` 同步
   （`updateList()` 會 post `GridCraftingCpuChange`，AE2 收到只是把 `updateList` 設 true，下一 tick 才重建）。

這個空窗就是「CPU 短暫消失」。

分析依據：GTOCore repo 早期提交 `592d908b`（多方块CPU）隨庫附的 **未加密** `libs/gtolib-1.0.jar`。
現行 gtolib 已加密（方法變 `native`，實作在 `native0/native/*.bin`），只能由欄位／方法簽名與常數池
（仍引用 `isBusy`／`isDestroyed`／`setDestroyed`／`GridCraftingCpuChange`）判斷設計未變。

## 修法

在 `CraftingService.onServerEndTick` 的 **HEAD** 每 tick 檢查：某個 part 的暴露清單裡若沒有可用
（未銷毀、未忙碌）的 CPU，就從完整清單裡挑一顆空閒的**加進暴露清單**，並把 `updateList` 設 true，
讓 AE2 在**同一 tick** 重建 CPU 集合。

補完之後暴露清單的空閒數剛好是 1 —— 正好是 gtolib 自己「空閒數 != 1 才重建」的不觸發條件，
所以不會跟它打架，也維持「只暴露一顆空閒」的原設計。

掛 HEAD 的另一個好處：GTOCore 自己也注入同一個方法（在 `craftingLinks.values()` 那一下 cancel 掉偶數 tick），
但它的注入點在 `updateCPUClusters()` **之後**，所以我們設的 `updateList` 每 tick 都吃得到。

### 為什麼 gtolib 那側全部走反射

- `NL_gto_hugebus_fix` 1.0.0～1.0.2 實證：`com.gtolib.*`、`com.gtocore.*`、`com.gregtechceu.gtceu.*`
  **掛不上 mixin**（同一份 jar 內掛 `appeng.*` 的對照組正常）。
- gtolib 發行版的**私有成員名被改成非 ASCII 亂碼**（`javap` 顯示 `????`），不能用名字找欄位。
  所以完整清單那個欄位是靠**型別 `it.unimi.dsi.fastutil.objects.ObjectArrayList`** 認出來的
  —— gtolib 26.7.4 實測，整個類別剛好只有這一個 `ObjectArrayList` 欄位。
- 對不上就自動停用（印一行 ERROR），不會亂動。

## 驗證

進世界後看 `latest.log`（訊息一律 ERROR 級別，因為 GTO 會吞掉載入階段的 INFO）：

```
[cpufix] 自檢 1/2：mixin 已套用到 appeng.me.service.CraftingService ✓
[cpufix] 自檢 2/2：已對上 gtolib 的合成CPU接口，完整 CPU 清單欄位型別 = ObjectArrayList ✓ 修復生效中。
```

實際補位時（前 5 次會逐次記錄，之後只計數，停機印總次數）：

```
[cpufix] 補位 #1：合成CPU接口 BlockPos{...} 的暴露 CPU 全忙（暴露 3 顆 / 共 256 顆），已補上一顆空閒 CPU（可用 ... bytes）。
```

「mixin 有沒有套用」只認自檢 1/2 那行（標記介面 `CpuFixApplied` + `isAssignableFrom`），
不要靠「有沒有印出補位記錄」——沒補位有可能只是這段時間剛好沒下單。

## 安裝

建置後把 `dist\` 或 `_NL_mod\1.20.1\forge\` 裡的 jar 放進
`E:\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\`，**並刪掉舊版本的 jar**（同 modid 兩份會崩）。

## 已知未修

`setThread(n)` 縮小分裂數時，index ≥ n 的 cluster 會被直接 `setDestroyed(true)` 而不檢查 `isBusy()`
（多方塊解體走 `setThread(0)` 同理），正在跑的合成訂單可能被丟掉。那條在 gtolib 的 `setThread` 裡，
掛不上也改不了 → 只能提上游 issue。
