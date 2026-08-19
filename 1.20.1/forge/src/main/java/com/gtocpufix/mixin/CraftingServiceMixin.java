package com.gtocpufix.mixin;

import com.gtocpufix.CpuFix;
import com.gtocpufix.CpuFixApplied;

import appeng.api.networking.IGrid;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * 唯一的修復掛點。<b>掛 AE2（{@code appeng.*}）</b>——依 {@code NL_gto_hugebus_fix} 的實證，
 * {@code com.gtolib.*}、{@code com.gtocore.*}、{@code com.gregtechceu.gtceu.*} 全部掛不上，
 * 所以 gtolib 那側一律走反射（見 {@link CpuFix}）。
 *
 * <p><b>掛點選擇</b>：{@code onServerEndTick} 的 HEAD。理由：
 *
 * <ul>
 * <li>AE2 在這個方法一開頭就是「{@code if (updateList) { updateList = false; updateCPUClusters(); }}」，
 * 我們在 HEAD 補完空閒 CPU 再把 {@code updateList} 設 true，<b>同一 tick</b> 就會重建 CPU 集合，
 * 不用多等一 tick。</li>
 * <li>GTOCore 自己也對這個方法注入（在 {@code craftingLinks.values()} 那一下 cancel 掉偶數 tick），
 * 但它的注入點在 {@code updateCPUClusters()} <b>之後</b>，所以我們設的 {@code updateList}
 * 每 tick 都吃得到，不會被那個 cancel 吃掉。</li>
 * <li>GTOCore 對 {@code updateCPUClusters()} 是 {@code @Overwrite}——我們完全不碰那個方法，
 * 只餵資料給它（它會從 part 的 {@code getClusters()} 重新讀），兩邊不會打架。</li>
 * </ul>
 *
 * <p>callback 只收 {@code CallbackInfo}（不接目標的 {@code MinecraftServer} 參數）：
 * GTOCore 的 {@code CraftingServiceMixin} 對同一個方法就是這樣寫且線上正常運作。
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin implements CpuFixApplied {

    @Shadow
    @Final
    private IGrid grid;

    @Shadow
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow
    private boolean updateList;

    @Inject(method = "onServerEndTick", at = @At("HEAD"), remap = false)
    private void gtocpufix$refillIdleCpu(CallbackInfo ci) {
        if (CpuFix.refillIdleCpu(grid, craftingCPUClusters)) {
            // 補進去的那顆還沒進 AE2 的 CPU 集合；讓它在這一 tick 立刻重建。
            updateList = true;
        }
    }
}
