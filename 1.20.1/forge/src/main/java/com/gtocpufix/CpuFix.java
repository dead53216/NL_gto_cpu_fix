package com.gtocpufix;

import appeng.api.networking.IGrid;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 修復邏輯本體。掛點在 {@code mixin.CraftingServiceMixin}（掛 AE2 的
 * {@code appeng.me.service.CraftingService}）。
 *
 * <p><b>Bug：ME超算核心分裂出的 CPU 會短暫全部消失。</b>超算核心真正持有 CPU 的是
 * 「合成CPU接口」這個 part（{@code com.gtolib.api.machine.impl.part.CraftingInterfacePartMachine}）。
 * 它內部有兩份清單：
 *
 * <ul>
 * <li><b>完整清單</b>（{@code ObjectArrayList} 欄位）：分裂出來的全部 cluster，數量是 2^(分裂層數)，
 * 可以到好幾百顆；</li>
 * <li><b>暴露清單</b>（{@code getClusters()} 回傳的 {@code List}）：只有「<b>忙碌中的全部</b>
 * ＋<b>恰好一顆空閒</b>」。AE2 的 {@code CraftingService} 只看得到這一份。</li>
 * </ul>
 *
 * 只暴露一顆空閒 CPU 是刻意的（否則 ME 終端會被幾百顆 CPU 洗版）。問題出在<b>補位的時機</b>：
 * 重建暴露清單的 {@code updateList()} 掛在 part 自己的週期 tick 上
 * （舊版是 {@code getOffsetTimer() % 10 == 0}，現行版改用 {@code ConditionalSubscriptionHandler}
 * 的 cycle），而且只有在「暴露清單裡空閒數 != 1」時才重建。於是：
 *
 * <ol>
 * <li>那顆唯一暴露的空閒 CPU 被下單佔用 → 變忙碌 → 暴露清單裡一顆空閒也沒有；</li>
 * <li>要等 part 下一次週期 tick 才補上下一顆空閒 CPU，再 +1 tick 讓 {@code CraftingService} 同步；</li>
 * <li>這段空窗期內 ME 終端的 CPU 列表看不到 CPU，新的合成請求也拿不到 CPU。</li>
 * </ol>
 *
 * <p><b>修法</b>：在 {@code CraftingService.onServerEndTick} 的 HEAD 每 tick 檢查一次，
 * 發現某個 part 的暴露清單裡沒有可用（未銷毀、未忙碌）的 CPU，就從被藏起來的完整清單裡
 * <b>補一顆空閒的進暴露清單</b>，並把 {@code updateList} 設為 true 讓同一 tick 立刻同步。
 * 補完之後暴露清單的空閒數剛好是 1，gtolib 自己的週期重建條件（空閒數 != 1）不會被觸發，
 * 不會跟它打架，也維持它「只暴露一顆空閒」的原設計。
 *
 * <p><b>為什麼全部走反射</b>：gtolib 是閉源且加密的（方法都變 {@code native}，實作在
 * {@code native0/native/*.bin}），<b>私有成員名在發行版被改成非 ASCII 亂碼</b>，而且
 * 依 {@code NL_gto_hugebus_fix} 的實證，{@code com.gtolib.*}、{@code com.gtocore.*}、
 * {@code com.gregtechceu.gtceu.*} 一律掛不上 mixin。所以：唯一的 mixin 掛點在 {@code appeng.*}，
 * gtolib 那側只用反射，而且<b>完整清單那個欄位是靠「型別是 {@code ObjectArrayList}」找出來的</b>，
 * 不靠欄位名（名字已經被混淆掉了）。gtolib 26.7.4 實測：整個類別剛好只有一個 {@code ObjectArrayList} 欄位。
 */
public final class CpuFix {

    private static final Logger LOG = LogManager.getLogger("gtocpufix");

    /** 修復掛點所在類別（AE2 側）。 */
    static final String AE2_CRAFTING_SERVICE = "appeng.me.service.CraftingService";

    /** 超算核心裡真正持有 CPU 的 part（gtolib 側，閉源加密）。 */
    static final String CPU_INTERFACE = "com.gtolib.api.machine.impl.part.CraftingInterfacePartMachine";

    /** 完整 cluster 清單的欄位型別；欄位名被混淆，只能靠型別認。 */
    private static final String FULL_LIST_TYPE = "it.unimi.dsi.fastutil.objects.ObjectArrayList";

    /** 前幾次補位印詳細訊息，之後只計數，免得洗版。 */
    private static final int VERBOSE_LIMIT = 5;

    private static final AtomicLong REFILLS = new AtomicLong();

    private static boolean resolved;
    private static boolean disabled;
    private static String disabledReason;

    private static Class<?> partClass;
    private static Method getClustersMethod;
    private static Field fullListField;

    /** 暴露清單不給改時的退路：直接把備用 CPU 塞進 AE2 的 CPU 集合（每 tick 補，不設 updateList）。 */
    private static boolean exposedImmutable;

    private CpuFix() {}

    public static void logLoaded() {
        report("[cpufix] 已載入 v1.0.0：修 ME超算核心分裂 CPU 後「暴露清單沒有空閒 CPU」的空窗。進世界時會印自檢結果。");
    }

    /**
     * 每個 grid 每 tick 呼叫一次。
     *
     * @param serviceCpus AE2 {@code CraftingService} 自己那份 CPU 集合，只有退路模式才會動到。
     * @return true 表示有補位，呼叫端要把 {@code updateList} 設成 true 讓 AE2 立刻重建 CPU 集合。
     */
    public static boolean refillIdleCpu(IGrid grid, Set<CraftingCPUCluster> serviceCpus) {
        if (grid == null || !resolve()) return false;

        Set<?> parts;
        try {
            parts = grid.getMachines(partClass);
        } catch (Throwable t) {
            disable("向 grid 取合成CPU接口失敗：" + t);
            return false;
        }
        if (parts == null || parts.isEmpty()) return false;

        boolean changed = false;
        for (Object part : parts) {
            changed |= refillOne(part, serviceCpus);
        }
        return changed;
    }

    private static boolean refillOne(Object part, Set<CraftingCPUCluster> serviceCpus) {
        List<?> exposed;
        List<?> full;
        try {
            exposed = (List<?>) getClustersMethod.invoke(part);
            full = (List<?>) fullListField.get(part);
        } catch (Throwable t) {
            disable("讀取合成CPU接口的 CPU 清單失敗：" + t);
            return false;
        }

        // exposed == full 代表這版 gtolib 沒有「藏起來的 CPU」這個設計 → 沒有要修的東西。
        if (exposed == null || full == null || exposed == full) return false;
        if (full.size() <= exposed.size()) return false;
        if (hasIdle(exposed)) return false;

        CraftingCPUCluster spare = findIdle(full, exposed);
        if (spare == null) return false; // 分裂出來的 CPU 真的全在忙 → 不是這個 bug，別插手

        if (!exposedImmutable) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> sink = (List<Object>) exposed;
                sink.add(spare);
                logRefill(part, exposed.size(), full.size(), spare);
                return true;
            } catch (UnsupportedOperationException e) {
                exposedImmutable = true;
                report("[cpufix] gtolib 的暴露清單不可變，改用退路：直接補進 AE2 的 CPU 集合。");
            }
        }

        // 退路：AE2 每次重建 CPU 集合都會把這顆洗掉，所以下一 tick 會再補一次（Set，重複 add 無害）。
        if (serviceCpus != null && serviceCpus.add(spare)) {
            logRefill(part, exposed.size(), full.size(), spare);
        }
        return false;
    }

    private static boolean hasIdle(List<?> clusters) {
        for (Object o : clusters) {
            if (o instanceof CraftingCPUCluster c && !c.isDestroyed() && !c.isBusy()) return true;
        }
        return false;
    }

    private static CraftingCPUCluster findIdle(List<?> full, List<?> exposed) {
        for (Object o : full) {
            if (!(o instanceof CraftingCPUCluster c)) continue;
            if (c.isDestroyed() || c.isBusy()) continue;
            if (containsIdentity(exposed, c)) continue;
            return c;
        }
        return null;
    }

    private static boolean containsIdentity(List<?> list, Object target) {
        for (Object o : list) {
            if (o == target) return true;
        }
        return false;
    }

    private static void logRefill(Object part, int exposedSize, int fullSize, CraftingCPUCluster spare) {
        long n = REFILLS.incrementAndGet();
        if (n > VERBOSE_LIMIT) return;
        report("[cpufix] 補位 #{}：{} 的暴露 CPU 全忙（暴露 {} 顆 / 共 {} 顆），已補上一顆空閒 CPU（可用 {} bytes）。",
                n, describe(part), exposedSize, fullSize, spare.getAvailableStorage());
        if (n == VERBOSE_LIMIT) {
            report("[cpufix] 之後的補位不再逐次記錄，停機時會印總次數。");
        }
    }

    /** 盡量印出機器座標；拿不到就退回類別名。 */
    private static String describe(Object part) {
        try {
            Object pos = part.getClass().getMethod("getPos").invoke(part);
            if (pos != null) return "合成CPU接口 " + pos;
        } catch (Throwable ignored) {
            // gtceu 的 getPos 拿不到就算了，不影響修復
        }
        return "合成CPU接口";
    }

    private static boolean resolve() {
        if (resolved) return !disabled;
        resolved = true;

        ClassLoader cl = CpuFix.class.getClassLoader();
        try {
            partClass = Class.forName(CPU_INTERFACE, false, cl);
        } catch (Throwable t) {
            disable("找不到 " + CPU_INTERFACE + "（不是 GTO 環境？）：" + t);
            return false;
        }
        try {
            getClustersMethod = partClass.getMethod("getClusters");
            getClustersMethod.setAccessible(true);
        } catch (Throwable t) {
            disable("找不到 getClusters()：" + t);
            return false;
        }

        // 私有欄位名在發行版被混淆成亂碼，只能靠型別認：整個類別只有一個 ObjectArrayList 欄位。
        Field found = null;
        for (Field f : partClass.getDeclaredFields()) {
            if (!FULL_LIST_TYPE.equals(f.getType().getName())) continue;
            if (found != null) {
                disable("完整 CPU 清單欄位不唯一（有多個 " + FULL_LIST_TYPE + "），不敢亂猜");
                return false;
            }
            found = f;
        }
        if (found == null) {
            disable("找不到完整 CPU 清單欄位（型別 " + FULL_LIST_TYPE + "）");
            return false;
        }
        try {
            found.setAccessible(true);
        } catch (Throwable t) {
            disable("完整 CPU 清單欄位無法存取：" + t);
            return false;
        }
        fullListField = found;
        return true;
    }

    private static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            disabledReason = reason;
            report("[cpufix] 停用修復：{}", reason);
        }
    }

    public static void selfCheck() {
        ClassLoader cl = CpuFix.class.getClassLoader();

        try {
            Class<?> cls = Class.forName(AE2_CRAFTING_SERVICE, false, cl);
            if (CpuFixApplied.class.isAssignableFrom(cls)) {
                report("[cpufix] 自檢 1/2：mixin 已套用到 {} ✓", AE2_CRAFTING_SERVICE);
            } else {
                report("[cpufix] 自檢 1/2：mixin 沒有套用到 {} ✗ 修復不會生效，請回報這行。", AE2_CRAFTING_SERVICE);
            }
        } catch (Throwable t) {
            report("[cpufix] 自檢 1/2：載入不到 {}（{}）。", AE2_CRAFTING_SERVICE, t.toString());
        }

        if (resolve()) {
            report("[cpufix] 自檢 2/2：已對上 gtolib 的合成CPU接口，完整 CPU 清單欄位型別 = {} ✓ 修復生效中。",
                    fullListField.getType().getSimpleName());
        } else {
            report("[cpufix] 自檢 2/2：對不上 gtolib（{}）✗ 修復不會生效，請回報這行。", disabledReason);
        }
    }

    public static void logSummary() {
        long n = REFILLS.get();
        if (n > 0) {
            report("[cpufix] 本次遊玩共補位 {} 次（每次都是 ME超算核心暴露的 CPU 全忙、空窗被補上）。", n);
        }
    }

    /**
     * 一律用 ERROR 級別。實證：GTO 整合包會吞掉 mod 構造子階段的 INFO，
     * 用 INFO 診斷會被誤判成「mixin 沒生效」。
     */
    private static void report(String msg, Object... args) {
        LOG.error(msg, args);
    }
}
