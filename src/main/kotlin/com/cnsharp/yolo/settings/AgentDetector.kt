package com.cnsharp.yolo.settings

import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.TimeUnit

/**
 * 运行时探测：命令能否在 PATH（或绝对路径）上解析出来。
 * 直接用 “command -v”（macOS / Linux，经登录 shell 以兼容 nvm / Homebrew 等通过
 * profile 注入的 PATH）/“where”（Windows）做解析，
 * 不实际执行命令本身 —— 只回答“这个命令找不找得到”，不做版本探测、不触发 REPL。
 */
object AgentDetector {

    /** 命令能否在 PATH 上解析到（等价 resolvePath(command) != null）。 */
    fun isOnPath(command: String): Boolean = resolvePath(command) != null

    /**
     * 解析命令的绝对路径（含登录 shell 注入的 PATH，兼容 nvm 等）。
     * 找不到返回 null。用于把「命令」展示成可确认的绝对路径，以及 isOnPath 的存在性判断。
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
}
