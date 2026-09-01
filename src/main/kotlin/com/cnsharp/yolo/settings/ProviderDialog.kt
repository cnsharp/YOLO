package com.cnsharp.yolo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.*

/**
 * Secondary dialog opened from the Agents settings tab's "Providers…" button (enabled only for an agent that
 * is installed AND proxy-able). Lets the user bind the current agent to one of their [LlmProvider]s, or create
 * a new one inline. All edits are kept on working copies and only committed to [AgentExtenderSettings.State]
 * (and the OS keystore via PasswordSafe) when OK is pressed — Cancel discards them.
 */
class ProviderDialog(
    private val agentId: String,
    agentDisplayName: String,
    private val agentCommand: String
) : DialogWrapper(null) {

    private val state = AgentExtenderSettings.getInstance().state

    /** Working copies so Cancel discards edits; envOverrides is deep-copied because it is a nested map. */
    private val workingProviders = state.providers.map { it.copy(envOverrides = it.envOverrides.toMutableMap()) }.toMutableList()
    private var workingBinding: String? = state.providerBindings[agentId.lowercase()]?.takeIf { it.isNotBlank() }

    private val providerCombo = ComboBox<Any>()
    private val nameField = JBTextField()
    private val familyCombo = ComboBox<ProviderFamily>()
    private val baseUrlField = JBTextField()
    private val updateKeyCheck = JCheckBox("设置/更新 API Key")
    private val apiKeyField = JPasswordField()
    private val modelField = JBTextField()
    private val envOverridesArea = JTextArea(4, 32)
    private val testResultLabel = JBLabel("")

    /** Provider currently shown in the editor; null means the "official default" (no binding). */
    private var currentProvider: LlmProvider? = null
    private var suppressListener = false

    init {
        title = "LLM Provider — $agentDisplayName"
        familyCombo.model = DefaultComboBoxModel(ProviderFamily.values())
        familyCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, familyLabel(value), index, isSelected, hasFocus)
        }
        buildComboModel()
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean): java.awt.Component =
                super.getListCellRendererComponent(list, providerLabel(value), index, isSelected, hasFocus)
        }
        providerCombo.addItemListener { e ->
            if (suppressListener) return@addItemListener
            if (e.stateChange == ItemEvent.SELECTED) onProviderSelected()
        }
        updateKeyCheck.addItemListener { apiKeyField.isEnabled = updateKeyCheck.isSelected }
        init()
        suppressListener = true
        providerCombo.selectedIndex = if (workingBinding != null) {
            val i = workingProviders.indexOfFirst { it.id == workingBinding }
            if (i >= 0) i + 1 else 0
        } else 0
        suppressListener = false
        onProviderSelected()
    }

    private fun familyLabel(v: Any?): String = when (v) {
        ProviderFamily.ANTHROPIC -> "Anthropic 协议 (Claude Code / OpenCode 等)"
        ProviderFamily.OPENAI -> "OpenAI 协议 (Codex / Aider / OpenAI 兼容)"
        ProviderFamily.GEMINI -> "Gemini 协议"
        ProviderFamily.CUSTOM -> "自定义 (任意兼容端点)"
        else -> v?.toString() ?: ""
    }

    private fun providerLabel(v: Any?): String = when (v) {
        OFFICIAL -> "（官方默认）"
        NEW -> "（＋ 新建 Provider…）"
        is LlmProvider -> v.name.ifBlank { v.id }
        else -> v?.toString() ?: ""
    }

    private fun buildComboModel() {
        val model = DefaultComboBoxModel<Any>()
        model.addElement(OFFICIAL)
        workingProviders.forEach { model.addElement(it) }
        model.addElement(NEW)
        providerCombo.model = model
    }

    private fun onProviderSelected() {
        commitCurrent()
        when (val sel = providerCombo.selectedItem) {
            OFFICIAL -> {
                currentProvider = null
                setEditorEnabled(false)
            }
            NEW -> {
                val np = LlmProvider(
                    id = "p-" + System.currentTimeMillis().toString(36),
                    name = "",
                    family = ProviderFamily.ANTHROPIC,
                    apiKeyRef = LlmProvider.newApiKeyRef()
                )
                workingProviders.add(np)
                buildComboModel()
                suppressListener = true
                providerCombo.selectedItem = np
                suppressListener = false
                currentProvider = np
                loadProvider(np)
            }
            is LlmProvider -> {
                currentProvider = sel
                loadProvider(sel)
            }
            else -> {
                currentProvider = null
                setEditorEnabled(false)
            }
        }
    }

    private fun loadProvider(p: LlmProvider) {
        setEditorEnabled(true)
        nameField.text = p.name
        familyCombo.selectedItem = p.family
        baseUrlField.text = p.baseUrl
        modelField.text = p.defaultModel
        updateKeyCheck.isSelected = false
        apiKeyField.text = ""
        apiKeyField.isEnabled = false
        envOverridesArea.text = p.envOverrides.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun setEditorEnabled(on: Boolean) {
        nameField.isEnabled = on
        familyCombo.isEnabled = on
        baseUrlField.isEnabled = on
        modelField.isEnabled = on
        updateKeyCheck.isEnabled = on
        envOverridesArea.isEnabled = on
        if (!on) {
            nameField.text = ""
            baseUrlField.text = ""
            modelField.text = ""
            envOverridesArea.text = ""
            apiKeyField.text = ""
            updateKeyCheck.isSelected = false
        }
    }

    /** Push the editor's current values into the provider object (mutated in place in workingProviders). */
    private fun commitCurrent() {
        val p = currentProvider ?: return
        p.name = nameField.text.trim()
        (familyCombo.selectedItem as? ProviderFamily)?.let { p.family = it }
        p.baseUrl = baseUrlField.text.trim()
        p.defaultModel = modelField.text.trim()
        val map = linkedMapOf<String, String>()
        envOverridesArea.text.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        p.envOverrides.clear()
        p.envOverrides.putAll(map)
        if (updateKeyCheck.isSelected) {
            val secret = String(apiKeyField.password).trim()
            if (secret.isNotEmpty()) LlmProvider.setApiKey(p.apiKeyRef, secret)
            else LlmProvider.clearApiKey(p.apiKeyRef)
        }
    }

    override fun createCenterPanel(): JComponent {
        val keyRow = JPanel(BorderLayout(6, 0)).apply {
            add(updateKeyCheck, BorderLayout.WEST)
            add(apiKeyField, BorderLayout.CENTER)
        }
        val note = LlmProviderSupport.injectionNote(agentCommand)
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("本 agent 使用的 Provider:"), providerCombo)
            .addComponent(JBLabel("（官方默认）表示使用 agent 自身后端，不注入任何环境变量。"))
            .apply { if (note != null) addComponent(JBLabel("<html><body width='420'>⚠ $note</body></html>")) }
            .addSeparator()
            .addLabeledComponent(JBLabel("名称:"), nameField)
            .addLabeledComponent(JBLabel("类型:"), familyCombo)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField)
            .addLabeledComponent(JBLabel("API Key:"), keyRow)
            .addLabeledComponent(JBLabel("模型:"), modelField)
            .addLabeledComponent(JBLabel("额外环境变量:"), JBScrollPane(envOverridesArea))
            .addComponent(JBLabel("每行一个 KEY=VALUE（主要用于「自定义」类型）。"))
            .addComponent(testButtonRow())
            .panel
    }

    private fun testButtonRow(): JComponent {
        val btn = JButton("Test Connection")
        btn.addActionListener { testConnection() }
        return JPanel(BorderLayout(6, 0)).apply {
            add(btn, BorderLayout.WEST)
            add(testResultLabel, BorderLayout.CENTER)
        }
    }

    private fun testConnection() {
        commitCurrent()
        val p = currentProvider ?: run { testResultLabel.text = "请先选择或新建一个 Provider"; return }
        val url = p.baseUrl.ifBlank { testResultLabel.text = "Base URL 为空"; return }
        testResultLabel.text = "测试中…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                LlmProvider.getApiKey(p.apiKeyRef)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..399
            }.getOrElse { false }
            ApplicationManager.getApplication().invokeLater {
                testResultLabel.text = if (reachable) "✓ 可达" else "✗ 不可达 / 需认证"
            }
        }
    }

    override fun doOKAction() {
        commitCurrent()
        val p = currentProvider
        val prevBinding = workingBinding
        if (p != null) {
            if (p.name.isBlank()) {
                Messages.showErrorDialog("Provider 名称不能为空", "LLM Provider")
                return
            }
            if (workingProviders.none { it.id == p.id }) workingProviders.add(p)
            // Rebinding to a different provider: drop the previous provider's config-file entries first.
            if (prevBinding != null && prevBinding != p.id) AgentConfigInjector.removeAll(agentCommand)
            state.providerBindings[agentId.lowercase()] = p.id
        } else {
            // Unbinding: remove any config-file entries written for this agent.
            if (prevBinding != null) AgentConfigInjector.removeAll(agentCommand)
            state.providerBindings.remove(agentId.lowercase())
        }
        state.providers = workingProviders
        AgentExtenderSettings.getInstance().fireChanged()
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent = providerCombo

    private companion object {
        private const val OFFICIAL = "（官方默认）"
        private const val NEW = "（＋ 新建 Provider…）"
    }
}
