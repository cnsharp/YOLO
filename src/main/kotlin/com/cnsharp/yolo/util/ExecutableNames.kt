package com.cnsharp.yolo.util

/**
 * Single source of truth for executable filename resolution.
 *
 * The terminal AI Agents dropdown matches injection rules by executable filename; multiple places
 * (AgentDetector / AgentExtenderConfigurable / TerminalSkipFlagCustomizer) need the same rule to get the
 * "binary name", so it is centralized here to avoid three drifting copies.
 */
private val EXE_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".ps1")

/** Get the executable filename: strip directory (both separators) + strip Windows executable suffixes (case-insensitive).
 *  npm on Windows generates both foo.cmd and foo.ps1, either of which may be resolved by PATH. */
fun baseName(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val suffix = EXE_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return name
    return name.dropLast(suffix.length)
}
