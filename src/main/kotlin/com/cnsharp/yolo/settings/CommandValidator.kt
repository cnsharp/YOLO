package com.cnsharp.yolo.settings

import com.cnsharp.yolo.YoloBundle.message

/**
 * Validate whether a custom tool's "command" is usable: confirm it can actually be executed
 * (not just checked against PATH).
 * Via AgentDetector.canExecute, the command is really run in an "interactive login shell" to confirm —
 * this detects binaries whose PATH was injected by rc files (.zshrc / .bashrc) (e.g. npm-globally
 * installed codebuddy), which a plain `command -v` (only sees the login shell's PATH) would miss.
 */
object CommandValidator {

    sealed interface Result {
        object Ok : Result
        data class NotFound(val detail: String) : Result
    }

    fun validate(command: String): Result {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Result.NotFound(message("error.command.empty"))
        return if (AgentDetector.canExecute(cmd)) Result.Ok
        else Result.NotFound(message("error.command.notOnPath", cmd))
    }
}
