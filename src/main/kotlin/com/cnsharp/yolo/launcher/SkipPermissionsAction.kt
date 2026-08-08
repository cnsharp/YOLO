package com.cnsharp.yolo.launcher

import com.cnsharp.yolo.settings.AgentExtenderSettings
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import javax.swing.Icon

/**
 * 全局 “Skip permissions” 切换状态（存储在 Settings 里）。
 *
 * 由 SkipToggleToolWindowInitializer 通过 toolWindowInitializer 扩展点
 * 装进终端标题栏，紧贴 AI Agents 下拉左边。
 *
 * 图标为小写 y：关闭时灰色，开启时红色 —— 视觉上与内置的 checkbox/toggle 行为一致，
 * 但用自定义图标让用户一眼就知道这是「跳过权限」开关。
 */
/* 文案不在这里硬编码：plugin.xml 里 <action> 已声明 <resource-bundle>，
 * 平台会按 action.<id>.text / .description 从 YoloBundle 里取，并跟随 IDE 语言切换。 */
class SkipPermissionsAction : ToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        AgentExtenderSettings.getInstance().state.skipEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AgentExtenderSettings.getInstance().state.skipEnabled = state
    }

    /** 状态只读内存里的 settings，不碰 PATH 探测，可在 EDT 上安全求值。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** 动态切换图标：关=灰 y，开=红 y。 */
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = if (isSelected(e)) AgentIcons.SKIP_Y_ON else AgentIcons.SKIP_Y_OFF
    }
}
