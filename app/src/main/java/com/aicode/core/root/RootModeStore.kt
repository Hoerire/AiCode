package com.aicode.core.root

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局「真实 root 模式」开关（持久化 + 进程内缓存）。
 *
 * 语义：
 *  - 关闭（默认）：容器由 proot 直接启动，`proot -0` 只是伪 root，容器内进程的内核 uid 仍是 app uid；
 *  - 开启：容器改由 `su -c "exec proot ..."` 启动。proot 本身以宿主 uid=0 运行，其 tracee（容器内所有
 *    进程，含终端 PTY、AI 的 Bash、git 等）继承 uid=0，因此是**真 root**（不是 ptrace 伪造的 getuid）。
 *    同时追加 /data 等宿主目录的绑定（见 LinuxContainerEngine.ROOT_EXTRA_BINDS），容器内可直接读写。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：本开关需要在 [com.aicode.feature.agent.domain.container.LinuxContainerEngine]
 * 构造 argv 时**同步**读取（proot/终端启动是同步路径，不能 await Flow），SharedPreferences 首次读后
 * 即缓存在内存，后续无 IO。
 */
object RootModeStore {

    private const val PREFS_NAME = "aicode_root_mode"
    private const val KEY_ENABLED = "root_mode_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _enabled.value
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _enabled.value = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
