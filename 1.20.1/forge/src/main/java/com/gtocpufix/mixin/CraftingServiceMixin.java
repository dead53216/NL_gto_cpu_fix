package com.gtocpufix.mixin;

import com.gtocpufix.CpuFix;
import com.gtocpufix.CpuFixApplied;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * [1.1.0] 第二個掛點：<b>每一次提交</b>都確保看得到一顆可用 CPU。
     *
     * <p>上面那個每 tick 只補一顆，而所有請求器是在同一 tick 的同一毫秒送單的——第一個把它
     * 占走之後，同 tick 後面每一個 {@code submitJob} 一律 {@code NO_SUITABLE_CPU_FOUND}。
     * 所以不管超算核心分裂出幾顆，併發開單數永遠是 1。掛在 HEAD 讓同 tick 的 M 個請求觸發
     * M 次補位，各拿到自己的一顆。
     *
     * <p>補進去的是 {@code craftingCPUClusters} 本體——AE2 這一次選 CPU 就是遍歷它；
     * 設 {@code updateList} 要等下一 tick 才重建，來不及。HEAD 在遍歷開始前，不會
     * 造成 ConcurrentModificationException。
     *
     * <p>指定了 {@code target} 的提交不插手（AE2 只會用那一顆，補了也沒用）。
     */
    @Inject(method = "submitJob", at = @At("HEAD"), remap = false)
    private void gtocpufix$ensureIdleForSubmit(ICraftingPlan job, ICraftingRequester requestingMachine,
                                               ICraftingCPU target, boolean prioritizePower,
                                               IActionSource src,
                                               CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (target != null || job == null) {
            return;
        }
        if (CpuFix.ensureIdleForSubmit(grid, craftingCPUClusters, job.bytes(), src)) {
            // [1.1.1] 這一次提交靠直接塞 craftingCPUClusters 解決，但那繞過了 AE2 自己的帳：
            // CraftingService 是 GridCraftingCpuChange 的**接收方**（收到就把 updateList 設 true、
            // 下一 tick 重建 CPU 集合）。我們無聲地改了那個集合，等於 AE2 的視角跟實際不一致，
            // 而且下次重建會把塞進去的洗掉。設 updateList 讓它下一 tick 正常重建一次——
            // 補進去的那顆同時也加進了 part 的暴露清單，所以重建後會被正常撿到。
            updateList = true;
        }
    }
}
