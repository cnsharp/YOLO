package com.cnsharp.yolo.settings

import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.TimeUnit

/**
 * 运行时探测：命令是否真的能执行（而不只是名字在 PATH 上）。
 * 直接执行 “<command> --version”：
 *   - macOS / Linux：用登录 shell（bash -lc）以兼容 nvm / Homebrew 等通过 profile 注入的 PATH；
 *   - Windows：用 “cmd /c <command> --version”（cmd 内建 where 也能定位 .exe/.cmd/.bat）。
 * 退出码 127（或 Windows 下找不到时的 9009）= command not found（未安装），
 * 其它任何退出码都说明二进制存在且能被执行（已安装）。带超时，避免交互式 REPL 卡住。
 */
object AgentDetector {

    fun isOnPath(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        val osType = if (SystemInfo.isWindows) OSType.Windows
        else if (SystemInfo.isMac) OSType.Mac
        else OSType.Linux
        return try {
            val pb = when (osType) {
                OSType.Windows -> ProcessBuilder("cmd", "/c", "$cmd --version")
                else -> ProcessBuilder("bash", "-lc", "$cmd --version")
            }
            pb.redirectErrorStream(true)
            val p = pb.start()
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return false
            }
            val code = p.exitValue()
            // 127 = 未找到（Unix）；9009 = 未找到（Windows cmd）
            code != 127 && code != 9009
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析命令的绝对路径（含登录 shell 注入的 PATH，兼容 nvm 等）。
     * 找不到返回 null。用于把「命令」展示成可确认的绝对路径。
     */
    fun resolvePath(command: String): String? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        return try {
            val pb = if (SystemInfo.isWindows)
                ProcessBuilder("cmd", "/c", "where ${cmd.replace("\"", "")}")
            else
                ProcessBuilder("bash", "-lc", "command -v '${cmd.replace("'", "'\\''")}'")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            out.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private enum class OSType { Windows, Mac, Linux }
}
