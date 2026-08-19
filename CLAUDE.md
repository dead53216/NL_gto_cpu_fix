# NL mod 規則

CLAUDE.md 只放指引；實作細節寫在 `README.md`／程式碼。

- 通用規則見工作區根 `CLAUDE.md`（本檔只列 mod 專屬）。

## mod 專屬約束

- 僅 `1.20.1/forge` 單平台（GTO 整合包專用），**不抽 common**；建置用 ModDevGradle legacyforge（非 ForgeGradle）。
- modid `gto_cpu_fix`、package `com.gtocpufix`、log 前綴 `[cpufix]`。簡稱用 `cpu`。
- **mixin 只准掛 `appeng.*`。** `com.gtocore.*`、`com.gtolib.*`、`com.gregtechceu.gtceu.*` 全部掛不上
  （`NL_gto_hugebus_fix` 1.0.0～1.0.2 三次實證，同一份 jar 內掛 `appeng.*` 的對照組正常）。
  碰 GTOCore／gtolib 的地方一律走反射＋類名字串。
- **`@Mixin` 一律用 `value = X.class` 類別字面量，禁止用 `targets = "字串"`**（字串寫法會無聲失效）。
- 依賴只需 AE2（15.267.4，與整合包 jarJar 進 gtocore 的同版）。
- mixin config 保持 `required: true` + `defaultRequire: 1`。但注意：**目標類找不到只會 WARN 然後靜靜跳過**，
  所以一定要留標記介面自檢（`CpuFixApplied` + `isAssignableFrom`）。
- **診斷訊息一律用 ERROR 級別**：GTO 會吞掉 mod 構造子階段的 INFO，用 INFO 會被誤判成 mixin 沒生效。
- **gtolib 私有成員名在發行版是非 ASCII 亂碼**（`javap` 顯示 `????`），永遠不要用名字找它的私有欄位／方法；
  只能靠型別或公開簽名認。對不上就停用並印一行 ERROR，不要硬猜。
- 只補「暴露清單沒有空閒 CPU」這一種情況，補**一顆**就好：補到空閒數剛好 1，
  正是 gtolib 自己「空閒數 != 1 才重建」的不觸發條件，才不會跟它互相打架。
- 建置與安裝：更新後把 jar 直接放進 `E:\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\`，
  **並刪掉舊版本 jar**（同 modid 兩份會崩）。
