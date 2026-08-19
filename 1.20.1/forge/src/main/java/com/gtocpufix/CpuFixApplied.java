package com.gtocpufix;

/**
 * 空標記介面。mixin 會把它加到 AE2 的 {@code CraftingService} 上，
 * 自檢就能用 {@code isAssignableFrom} <b>直接斷定 mixin 到底有沒有套用</b>——
 * 不必靠「有沒有印出補位記錄」這種會被「這段時間剛好沒觸發」混淆的間接證據。
 *
 * <p>（同樣的招數在 {@code NL_gto_hugebus_fix} 1.0.2 立過大功：一次就把
 * 「本 mod 設定有問題」和「GTO 那側掛不上」分開。）
 */
public interface CpuFixApplied {}
