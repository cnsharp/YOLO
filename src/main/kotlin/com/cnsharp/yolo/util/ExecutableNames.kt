package com.cnsharp.yolo.util

/**
 * 可执行文件名解析的单一来源。
 *
 * 终端 AI Agents 下拉按可执行文件名匹配注入规则，多处（AgentDetector / AgentExtenderConfigurable /
 * TerminalSkipFlagCustomizer）都要用同一套规则取「二进制名」，故集中在此，避免三处拷贝漂移。
 */
private val EXE_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".ps1")

/** 取可执行文件名：去目录（两种分隔符）+ 去 Windows 可执行后缀（大小写不敏感）。
 *  npm 在 Windows 上会同时生成 foo.cmd 与 foo.ps1，二者都可能被 PATH 解析到。 */
fun baseName(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val suffix = EXE_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return name
    return name.dropLast(suffix.length)
}
