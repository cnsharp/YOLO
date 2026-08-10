package com.cnsharp.yolo.settings

import com.cnsharp.yolo.YoloBundle.message

/**
 * 校验自定义工具的「命令」是否可解析：确认它在 PATH / 绝对路径上找得到。
 * 经 AgentDetector.isOnPath 直接用 command -v / where 解析（登录 shell 兼容
 * nvm / Homebrew 注入的 PATH），不实际执行命令本身。
 */
object CommandValidator {

    sealed interface Result {
        object Ok : Result
        data class NotFound(val detail: String) : Result
    }

    fun validate(command: String): Result {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Result.NotFound(message("error.command.empty"))
        return if (AgentDetector.isOnPath(cmd)) Result.Ok
        else Result.NotFound(message("error.command.notOnPath", cmd))
    }
}
