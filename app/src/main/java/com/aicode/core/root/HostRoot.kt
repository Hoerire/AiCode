package com.aicode.core.root

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宿主（Android 系统）root 执行通道。
 *
 * 为什么必须有这条通道：容器是 `proot` 起的，`proot -0` 只是 ptrace 层把 `getuid()` 的返回值
 * 伪造成 0，内核 cred 仍是 app uid。容器内哪怕 exec 到宿主的 `/system/bin/su`，新进程也仍是
 * proot 的 tracee，路径会被 proot 翻译回 rootfs —— 拿到的 shell 看到的是容器文件系统，不是
 * 宿主，等于假 root。真正的 uid=0 只能在 **proot 之外** 由 app 进程 `ProcessBuilder` 拉起
 * （Magisk/KernelSU/APatch 的 su 服务端在本进程之外 fork，不受 proot ptrace 约束）。
 *
 * 因此：AI 的 `Bash` 工具识别 `root <cmd>` / `sudo <cmd>` 前缀，改由本类在宿主上下文执行，
 * 工作目录仍是同一物理工作区，输出格式与容器执行一致。
 */
@Singleton
class HostRoot @Inject constructor() {

    /** root 授权探测结果。[suPath] 为实际命中的 su 可执行文件。 */
    data class RootState(
        val granted: Boolean,
        val uid: Int?,
        val suPath: String?,
        val detail: String
    ) {
        companion object {
            val UNKNOWN = RootState(false, null, null, "未探测")
            val DENIED = RootState(false, null, null, "无 su 或未授权")
        }
    }

    /** 一次宿主 root 执行的结果。[exitCode] 为 null 表示超时/启动失败。 */
    data class Result(val output: String, val exitCode: Int?, val truncated: Boolean)

    @Volatile
    private var cached: RootState? = null

    /** 最近一次探测结果（不做超时等待，供 UI / 命令分流同步判断）。 */
    fun stateCached(): RootState = cached ?: RootState.UNKNOWN

    fun isGrantedCached(): Boolean = cached?.granted == true

    /**
     * 候选 su 路径。顺序即优先级：
     *  - `/system/bin/su`：Magisk / KernelSU 的标准注入位（Android 11+ 无 /sbin）；
     *  - `/debug_ramdisk/su`：Magisk 24+ 的 ramdisk 别名；
     *  - `/system/xbin/su`、`/sbin/su`：老版本/其他方案；
     *  - `su`：走 PATH，兜底（部分 ROM 由 shim 提供）。
     */
    private val candidates = listOf(
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/system/xbin/su",
        "/sbin/su",
        "su"
    )

    /** 命中的 su，null 表示系统里没有 root 方案。 */
    fun suBinary(): String? = candidates.firstOrNull { it == "su" || File(it).canExecute() }

    /**
     * 探测 root 可用性：`su -c id` 是否返回 `uid=0`。
     *
     * 首次调用会触发 Magisk 的授权弹窗，故 [timeoutMs] 默认给 15s（用户点授权的时间）；
     * 结果缓存，除非显式 [force] 重探。
     */
    fun probe(force: Boolean = false, timeoutMs: Long = 15_000L): RootState {
        cached?.let { if (!force) return it }
        val su = suBinary()
        if (su == null) {
            val r = RootState(false, null, null, "系统未找到 su（未安装 Magisk/KernelSU/APatch？）")
            cached = r
            FileLogger.w(TAG, "root 探测失败：${r.detail}")
            return r
        }
        val out = runCatching {
            buildProcess(su, "id", null).start().let { p ->
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    return@let "__TIMEOUT__"
                }
                p.inputStream.bufferedReader().readText().trim().ifEmpty { "__EMPTY__" }
            }
        }.getOrElse { "__ERR__:${it.message}" }

        val granted = ROOT_UID_RE.containsMatchIn(out)
        val uid = UID_RE.find(out)?.groupValues?.get(1)?.toIntOrNull()
        val r = when {
            granted -> RootState(true, uid, su, "root 已授权（$su）")
            out == "__TIMEOUT__" -> RootState(false, uid, su, "su 授权超时（请到 Magisk 里给本应用授权）")
            out == "__EMPTY__" -> RootState(false, uid, su, "su 无输出（已被拒绝授权）")
            else -> RootState(false, uid, su, "su 执行失败：${out.take(200)}")
        }
        cached = r
        FileLogger.i(TAG, "root 探测：granted=${r.granted} uid=${r.uid} ${r.detail}")
        return r
    }

    /**
     * 在宿主上下文同步执行 [command]，cwd 为宿主绝对路径 [workdir]（即工作区的物理路径，
     * 与容器内 `~/workspace` 指向同一目录）。
     *
     * [maxChars] 为输出上限，超出则截断并置 [Result.truncated]（与容器侧限幅口径一致）。
     */
    fun runSync(
        command: String,
        workdir: String?,
        timeoutMs: Long,
        maxChars: Int = 400_000
    ): Result = runCatching {
        val su = suBinary() ?: return Result("系统未找到 su", 127, false)
        val process = buildProcess(su, command, workdir).start()
        val sb = StringBuilder()
        var truncated = false
        // 与 stdout 并行读，避免大输出把管道写满导致子进程阻塞、waitFor 永久等
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (sb.length < maxChars) sb.append(line).append('\n') else truncated = true
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            reader.join(500)
            val partial = sb.toString()
            return Result(
                if (partial.isNotEmpty()) "$partial\n[命令超时（${timeoutMs}ms）已终止]"
                else "[命令超时（${timeoutMs}ms）已终止]",
                null,
                truncated
            )
        }
        reader.join(2_000)
        Result(sb.toString(), process.exitValue(), truncated)
    }.getOrElse {
        FileLogger.e(TAG, "宿主 root 执行异常: $command", it)
        Result("执行失败：${it.message}", null, false)
    }

    /** 流式版本：逐行下发，结束时发退出码。供 AI 的流式命令通道使用。 */
    fun runStream(command: String, workdir: String?, timeoutMs: Long): Flow<HostLine> = flow {
        val su = suBinary()
        if (su == null) {
            emit(HostLine.Line("系统未找到 su（未安装 Magisk/KernelSU/APatch？）"))
            emit(HostLine.Exit(127))
            return@flow
        }
        val process = buildProcess(su, command, workdir).start()
        val watchdog = Thread {
            // interrupt() from finally is the normal path (stream closed / coroutine cancelled):
            // an uncaught InterruptedException here kills the process (crash at HostRoot.kt:162).
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply { isDaemon = true; start() }
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) emit(HostLine.Line(line))
            }
            val code = runCatching { process.waitFor() }.getOrNull()
            emit(HostLine.Exit(code))
        } finally {
            watchdog.interrupt()
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /** 流式事件。 */
    sealed interface HostLine {
        data class Line(val text: String) : HostLine
        data class Exit(val code: Int?) : HostLine
    }

    /**
     * 构造 `su -c <script>` 进程。
     *
     * cwd 用 `cd '<workdir>' &&` 前缀注入而非 [ProcessBuilder.directory]：su 服务端常在
     * 自己的上下文里重置 cwd，只有 script 内的 cd 才是稳的。
     */
    private fun buildProcess(su: String, command: String, workdir: String?): ProcessBuilder {
        val script = if (workdir != null && workdir.isNotBlank()) {
            "cd ${shellQuote(workdir)} && $command"
        } else {
            command
        }
        return ProcessBuilder(su, "-c", script)
            .redirectErrorStream(true)
            // stdin 接空设备：su 的部分实现会读 stdin，留空管道会让无输入命令挂到超时
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "HostRoot"
        val ROOT_UID_RE = Regex("uid=0\\(root\\)|uid=0\\s")
        val UID_RE = Regex("uid=(\\d+)")
    }
}
