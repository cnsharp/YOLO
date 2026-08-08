package com.cnsharp.yolo.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * 终端标题栏上的齿轮按钮，紧贴 AI Agents 下拉右边，一键打开本插件的配置页。
 *
 * 由 SkipToggleToolWindowInitializer 通过 toolWindowInitializer 扩展点装入。
 * 按 Configurable 的类而不是显示名定位配置页：显示名会随 IDE 语言变化，类不会。
 */
/* 文案不在这里硬编码：plugin.xml 里 <action> 已声明 <resource-bundle>，
 * 平台会按 action.<id>.text / .description 从 YoloBundle 里取，并跟随 IDE 语言切换。 */
class OpenSettingsAction : AnAction(AllIcons.General.Settings) {

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, AgentExtenderConfigurable::class.java)
    }

    /** 没有 update 逻辑，图标与文案都是静态的，后台线程求值即可。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
