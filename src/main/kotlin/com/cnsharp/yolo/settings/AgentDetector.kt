package com.cnsharp.yolo.settings

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runtime detection: can the command actually be executed?
 *
 * Detection is cross-platform (macOS / Linux / Windows):
 *  - PATH check first (cheap): Windows uses `where`; Unix uses an interactive login shell's
 *    `command -v` so rc-defined PATH is honoured — npm's global bin, nvm, fnm, … added in
 *    ~/.zshrc / ~/.zprofile / ~/.bashrc / ~/.profile, which a GUI-launched IDE would otherwise
 *    miss.
 *  - If PATH misses, we actually RUN the command (`--version`, falling back to `-v` / `--help`
 *    / `-h`) and treat a 0 exit as "installed". This catches the rare case where `command -v`
 *    / `where` can't resolve a command that nonetheless runs.
 * The IDE is a GUI app and does not inherit the interactive shell's PATH, which is exactly why
 * an installed tool (e.g. codebuddy, added in ~/.zshrc) can look "not found" without this.
 */
object AgentDetector {

    /** Whether the command can actually be executed — PATH check first, execute fallback second. */
    fun canExecute(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        // 1) PATH check first (cheap): Windows=where, Unix=interactive login shell's command -v (honours rc-injected PATH).
        if (resolvePath(cmd) != null) return true
        // 2) If PATH misses, actually run the command once as a fallback (covers the rare case where command -v / where can't resolve but it still runs).
        return runVersionProbe(cmd)
    }

    /**
     * Fallback probe: run `<command> --version` (falling back to `-v` / `--help` / `-h`) and
     * return true on a 0 exit code — i.e. the binary ran. Only reached when the cheap PATH
     * check missed.
     */
    private fun runVersionProbe(command: String): Boolean {
        val probes = listOf("--version", "-v", "--help", "-h")
        return if (SystemInfo.isWindows) {
            // Windows: `cmd /c "<command> <probe>"`; PATHEXT lets cmd resolve .cmd / .exe / .bat.
            for (probe in probes) {
                if (runForExit("cmd", "/c", "$command $probe")) return true
            }
            false
        } else {
            // Unix: a single shell invocation tries every probe; user's default shell first,
            // bash as fallback. The interactive login shell honours rc-defined PATH.
            val quoted = "'${command.replace("'", "'\\''")}'"
            val script = "for p in --version -v --help -h; do $quoted \$p >/dev/null 2>&1 && exit 0; done; exit 1"
            for ((shell, flag) in interactiveShells()) {
                if (runForExit(shell, flag, script)) return true
            }
            false
        }
    }

    /** (shell, login+interactive flag) pairs to try; the user's default shell comes first. */
    private fun interactiveShells(): List<Pair<String, String>> {
        val default = System.getenv("SHELL")?.let { File(it).name }
        return listOfNotNull(default, "zsh", "bash").distinct().map { it to "-lic" }
    }

    /** Run `script` in the given shell; true iff it exits 0. We rely on the exit code, not stdout. */
    private fun runForExit(shell: String, flag: String, script: String): Boolean = try {
        val pb = ProcessBuilder(shell, flag, script)
        // Stop a pager (triggered by e.g. --help) from hanging the probe waiting on a TTY.
        pb.environment()["PAGER"] = "cat"
        pb.environment()["GIT_PAGER"] = "cat"
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().readText() // drain so the process can finish
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /**
     * Resolve the command's absolute path (for display / fallback).
     *  - Windows: `cmd /c where` (uses the inherited PATH / PATHEXT).
     *  - Unix: an interactive login shell's `command -v`, so rc-defined PATH is honoured.
     * Returns null if not found.
     */
    fun resolvePath(command: String): String? {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        return try {
            val pb = if (SystemInfo.isWindows)
                ProcessBuilder("cmd", "/c", "where ${cmd.replace("\"", "")}")
            else {
                val shell = System.getenv("SHELL")?.let { File(it).name } ?: "bash"
                ProcessBuilder(shell, "-lic", "command -v '${cmd.replace("'", "'\\''")}'")
            }
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
