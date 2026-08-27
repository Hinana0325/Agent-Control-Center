package com.agentcontrolcenter.app.core.vendor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FairMemoryManager — 金标联盟「公平运行内存机制」适配。
 *
 * 背景：vivo/小米/OPPO/荣耀（移动智能终端生态联盟）于 2026-04 发布统一内存管理规范，
 * 对齐 Android 17 内存管控方向。机制分两级：
 *  - 内存预警（TRIM 广播）：应用触达预警条件时释放内存，避免后续被查杀
 *  - 查杀通知（KILL 广播）：应用即将被查杀，须立即保存现场数据，
 *    并在 3 秒内通过系统下发的 callback IBinder 回调处理结果，
 *    系统「收到回调或超时后」才执行查杀
 *
 * 适配要点（依据小米澎湃OS开发者平台《公平运行内存适配：开发者文档》pId=2304）：
 *  - 动态注册广播：`itgsa.intent.action.TRIM` / `itgsa.intent.action.KILL`（API 33+
 *    需 RECEIVER_EXPORTED，广播由系统侧服务发送）
 *  - 数据结构：extras 内 `common` Bundle 携带 notifyType/notifyId/reason/callback(IBinder)；
 *    `extra` Bundle 携带内存数值。notifyType=1000 物理内存（PSS，pss/pssLimit）、
 *    2000 Java 堆（heapAlloc/heapCapacity）
 *  - 回调协议：IBinder.transact(IBinder.FIRST_CALL_TRANSACTION, FLAG_ONEWAY)，
 *    Parcel 依次 writeInt(notifyType/notifyId/result) + writeBundle(extra)；
 *    系统超时 3s，超时即查杀
 *
 * 释放策略（对本应用）：
 *  - TRIM：清理可从 Room 重建的内存副本（传输层历史缓存）与环形缓冲
 *    （Analytics 埋点）——Agent 连接本身不动（核心功能）
 *  - KILL：本应用消息/会话实时落库（Room），无额外现场需要备份；
 *    立即回调 result=0 表示处理完成
 *
 * 不可重入：initialize 幂等（AtomicBoolean）；进程内单例。
 */
object FairMemoryManager {

    private const val TAG = "FairMemoryManager"

    /** 内存预警广播 action（金标联盟统一规范）。 */
    const val ACTION_TRIM = "itgsa.intent.action.TRIM"

    /** 查杀通知广播 action（金标联盟统一规范）。 */
    const val ACTION_KILL = "itgsa.intent.action.KILL"

    /** notifyType：物理内存（PSS）异常。 */
    const val NOTIFY_TYPE_PSS = 1000

    /** notifyType：Java 堆内存异常。 */
    const val NOTIFY_TYPE_HEAP = 2000

    /** 回调 result：处理成功（依据官方示例 reply(..., 0, ...)）。 */
    const val RESULT_OK = 0

    /** 回调协议 transact code（官方文档：TRANSACTION_EXCEPTION_REPLY）。 */
    private val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION

    /** 释放钩子：TRIM/KILL 广播触发时依次执行（内存释放/现场保存）。 */
    private interface ReleaseHook {
        val name: String
        fun release()
    }

    private class HookEntry(
        override val name: String,
        private val action: () -> Unit
    ) : ReleaseHook {
        override fun release() = action()
    }

    private val hooks = CopyOnWriteArrayList<ReleaseHook>()
    private val initialized = AtomicBoolean(false)

    /** 系统下发的回调 binder（linkToDeath 追踪系统侧死亡）。 */
    private var remote: IBinder? = null
    private var remoteLock = Any()

    private var handlerThread: HandlerThread? = null

    /**
     * 注册内存释放钩子（Application/各组件初始化时调用）。
     *
     * 钩子执行在广播分发线程（专用 HandlerThread），应快速完成：
     * 内存清理 → 返回；异步落库工作自行开协程。
     */
    fun addReleaseHook(name: String, action: () -> Unit) {
        hooks.add(HookEntry(name, action))
    }

    /**
     * 初始化：注册 TRIM/KILL 广播接收器（Application.onCreate 调用一次，幂等）。
     *
     * 广播处理跑在专用 HandlerThread，避免占用主线程或拖慢其他广播。
     */
    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val ht = HandlerThread(TAG).apply { start() }
        handlerThread = ht
        val handler = Handler(ht.looper)

        val filter = IntentFilter(ACTION_TRIM).apply { addAction(ACTION_KILL) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, null, handler)
            }
            Log.i(TAG, "fair-memory receiver registered (TRIM/KILL)")
        } catch (e: SecurityException) {
            // API 33+ 广播注册标志缺失/非法时系统抛出；本处已显式 RECEIVER_EXPORTED，
            // 仅保留厂商 ROM 差异化场景下的兜底日志
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TRIM, ACTION_KILL -> handleBroadcast(intent)
            }
        }
    }

    /**
     * 解析广播数据并执行释放/备份 + binder 回调。
     *
     * 数据契约：extras -> Bundle("common"){notifyType,notifyId,reason,action,callback}
     *                       + Bundle("extra"){pss,pssLimit | heapAlloc,heapCapacity}
     */
    private fun handleBroadcast(intent: Intent) {
        val data: Bundle = intent.extras ?: return
        val common: Bundle = data.getBundle("common") ?: return
        val extra: Bundle = data.getBundle("extra") ?: Bundle.EMPTY

        val notifyType = common.getInt("notifyType")
        val notifyId = common.getInt("notifyId")
        val reason = common.getString("reason")
        val callback: IBinder? = common.getBinder("callback")

        // 内存数值仅用于日志观测（本应用不做差异化处理，统一全量释放）
        when (notifyType) {
            NOTIFY_TYPE_PSS -> Log.w(TAG, "TRIM/KILL pss=${extra.getInt("pss")}KB " +
                "limit=${extra.getInt("pssLimit")}KB reason=$reason id=$notifyId")
            NOTIFY_TYPE_HEAP -> Log.w(TAG, "TRIM/KILL heap=${extra.getInt("heapAlloc")}KB " +
                "capacity=${extra.getInt("heapCapacity")}KB reason=$reason id=$notifyId")
        }

        // 1) 执行释放钩子（TRIM 释放内存；KILL 保存现场——本应用实时落库，钩子即释放）
        runHooks(intent.action ?: "")

        // 2) 通过系统 callback binder 回调处理结果（3s 超时前）
        if (callback != null) {
            linkRemote(callback)
            reply(notifyType, notifyId, RESULT_OK)
        } else {
            Log.w(TAG, "callback binder missing in common bundle")
        }
    }

    /**
     * 执行全部释放钩子；单钩子异常不阻断其余钩子与 binder 回调。
     *
     * @Suppress 说明：这里是刻意设计的故障隔离屏障 —— 钩子由应用内各组件注册，
     * release() 的异常类型不可枚举，且单个钩子失败不得阻断其余钩子与 3s 超时前的
     * binder 回调，必须捕获 Exception 全量兜底。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runHooks(action: String) {
        for (hook in hooks) {
            try {
                hook.release()
            } catch (e: Exception) {
                Log.w(TAG, "release hook '${hook.name}' failed on $action: ${e.message}")
            }
        }
    }

    /** 缓存系统回调 binder 并挂死亡通知（系统服务死亡时置空，避免向死 binder transact）。 */
    private fun linkRemote(callback: IBinder) {
        synchronized(remoteLock) {
            if (remote == null) {
                try {
                    callback.linkToDeath(deathRecipient, 0)
                } catch (e: RemoteException) {
                    Log.w(TAG, "linkToDeath failed: ${e.message}")
                }
                remote = callback
            }
        }
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            synchronized(remoteLock) {
                remote?.let {
                    try { it.unlinkToDeath(this, 0) } catch (_: Exception) { }
                }
                remote = null
            }
        }
    }

    /**
     * 通过 callback binder 将处理结果回传系统（官方回复协议）。
     *
     * Parcel 布局：writeInt(notifyType) → writeInt(notifyId) → writeInt(result) → writeBundle(extra)
     */
    private fun reply(notifyType: Int, notifyId: Int, result: Int) {
        val binder: IBinder = synchronized(remoteLock) { remote } ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            data.writeBundle(Bundle())
            binder.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY)
            reply.readException()
        } catch (e: RemoteException) {
            // binder 通道断开（系统查杀流程已结束/进程死亡），回调送达失败仅记日志
            Log.e(TAG, "reply failed: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
