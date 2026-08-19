package com.gtocpufix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 獨立修復 mod：不改 GTOCore、不動 gtolib。
 *
 * <p>修「ME超算核心分裂出的 CPU 會短暫從 ME 網路消失」。根因與修法見 {@link CpuFix}。
 */
@Mod(GtoCpuFixMod.MODID)
public final class GtoCpuFixMod {

    public static final String MODID = "gto_cpu_fix";

    public GtoCpuFixMod() {
        CpuFix.logLoaded();
        // 自檢排在 ServerStarting：這時已離開 mod 載入階段，log 一定出得來，
        // 且 gtolib 的類都已經可以載入（自檢要 Class.forName 它）。
        MinecraftForge.EVENT_BUS.addListener(GtoCpuFixMod::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(GtoCpuFixMod::onServerStopped);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        CpuFix.selfCheck();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        CpuFix.logSummary();
    }
}
