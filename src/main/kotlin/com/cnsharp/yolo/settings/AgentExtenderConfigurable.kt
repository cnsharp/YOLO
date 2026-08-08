package com.cnsharp.yolo.settings

import com.cnsharp.yolo.Yolo
import com.cnsharp.yolo.YoloBundle.message
import com.cnsharp.yolo.terminal.AgentIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.table.JBTable
import com.cnsharp.yolo.util.baseName
import com.intellij.util.ui.FormBuilder
import org.jetbrains.plugins.terminal.agent.TerminalAgent
import javax.swing.event.TableModelEvent
import java.awt.Component
import java.awt.image.BufferedImage
import javax.swing.GrayFilter
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * Settings | Tools | YOLO: AI Agents Extender
 *
 * 单一的 agent 合并列表，每行都可配置自己的 “Skip flag”（跳过权限参数）：
 *   - IDEA 内置 agent（动态取自 TerminalAgentProvider，只读、不可移除、图标用 IDEA 自带）
 *   - 本插件推广 agent（如 codebuddy，只读、不可移除）
 *   - 用户自定义工具（可编辑、可增删、图标可指定）
 * “Skip flag” 列的值：勾选终端工具栏 “Skip permissions” 时，会被注入到该 agent 的启动命令之后。
 * 已安装（在 PATH 上）的 agent 图标正常点亮，未安装的图标显示为灰色；有图标显示图标，没有则默认闪电。
 */
class AgentExtenderConfigurable : Configurable {

    private val toolsModel = DefaultTableModel(
        arrayOf(
            message("column.icon"), message("column.id"), message("column.displayName"),
            message("column.command"), message("column.baseArgs"),
            message("column.skipFlag"), message("column.iconPath")
        ),
        0
    )

    private val toolsTable = AgentsTable(toolsModel)

    /** 内置 agent 的 id（小写）集合与 id->图标 映射，reset() 时刷新，供判读/渲染使用。 */
    private var builtInIds: Set<String> = emptySet()
    private var builtInIconById: Map<String, Icon?> = emptyMap()
    /** 本插件推广 agent（如 codebuddy）的 id（小写）集合：与内置一样只读、不可移除、不可改图标。 */
    private var promotedIds: Set<String> = emptySet()

    private val statusLabel = JLabel("")

    private var panel: JComponent? = null

    /** 是否有未保存的改动：驱动平台 Apply 按钮的可用/置灰状态。 */
    private var modified = false
    /** reset() 重建表格时临时抑制 TableModelListener，避免把“加载”误判为“改动”。 */
    private var rebuilding = false
    /** autoFillSkipFlag 里 setValueAt 会再次触发 TableModelListener，用此标志防止递归处理。 */
    private var autoFilling = false

    override fun getDisplayName(): String = Yolo.NAME

    override fun createComponent(): JComponent {
        toolsTable.putClientProperty("terminateEditOnFocusLost", true)

        toolsTable.columnModel.getColumn(COL_ICON).cellRenderer = IconRenderer()
        toolsTable.columnModel.getColumn(COL_ICON).preferredWidth = 48
        toolsTable.columnModel.getColumn(COL_SKIP).preferredWidth = 200

        // 任意单元格改动（增删行、编辑、改图标）都标记为“有未保存改动”，使 Apply 按钮点亮；
        // 同时即时做重复检查，让用户在编辑当下就看到冲突，而不是等到 Apply 才发现。
        installListeners()

        val titleWithHelp = JPanel(HorizontalLayout(4)).apply {
            add(JBLabel(message("settings.agents.label")))
            add(ContextHelpLabel.create(message("settings.agents.help")))
        }

        val actionButtons = JPanel(HorizontalLayout(6)).apply {
            add(addToolButton())
            add(removeToolButton())
            add(validateButton())
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(titleWithHelp, JBScrollPane(toolsTable), true)
            .addComponent(actionButtons)
            .addComponent(iconChooserButton())
            .addComponent(statusLabel)
            .panel
        reset()
        return panel!!
    }

    // ── 锁定判定 ─────────────────────────────────────────────────

    /** 选中行是否为「锁定」agent：IDEA 内置 或 本插件推广（如 codebuddy）。
     *  这两类对用户而言等同于内置——只读、不可移除、不可改图标。 */
    private fun isLockedAgent(row: Int): Boolean {
        if (row < 0) return false
        val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim()?.lowercase() ?: ""
        return id in builtInIds || id in promotedIds
    }

    // ── 按钮 ───────────────────────────────────────────────────────

    private fun addToolButton(): JComponent {
        val button = JButton(message("button.add"))
        button.toolTipText = message("button.add.tooltip")
        button.addActionListener {
            toolsModel.addRow(arrayOf<Any>("", "", "", "", "", "", ""))
            val last = toolsModel.rowCount - 1
            toolsTable.selectionModel.setSelectionInterval(last, last)
            toolsTable.scrollRectToVisible(toolsTable.getCellRect(last, COL_ID, true))
            setStatus(message("status.added"), warn = false)
        }
        return button
    }

    private fun removeToolButton(): JComponent {
        val button = JButton(message("button.remove"))
        button.toolTipText = message("button.remove.tooltip")
        button.addActionListener {
            val row = toolsTable.selectedRow
            if (row < 0) {
                setStatus(message("status.selectRow"), warn = true)
                return@addActionListener
            }
            if (isLockedAgent(row)) {
                val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
                setStatus(message("status.cannotRemove", id), warn = true)
                return@addActionListener
            }
            toolsModel.removeRow(row)
            setStatus(message("status.removed"), warn = false)
        }
        return button
    }

    private fun iconChooserButton(): JComponent {
        val textField = JBTextField().apply {
            toolTipText = message("icon.field.tooltip")
        }
        val browse = JButton(message("button.browse"))
        browse.toolTipText = message("button.browse.tooltip")
        val note = JLabel("")
        fun syncEnabled() {
            val row = toolsTable.selectedRow
            val locked = isLockedAgent(row)
            textField.isEnabled = !locked
            browse.isEnabled = !locked
            note.text = if (locked && row >= 0) message("icon.locked") else ""
        }
        browse.addActionListener {
            val descriptor = FileChooserDescriptorFactory
                .createSingleFileDescriptor("svg")
                .withExtensionFilter(message("icon.chooser.title"), "svg", "png")
            val initial = textField.text?.takeIf { it.isNotBlank() && !it.startsWith("http", true) }?.let {
                LocalFileSystem.getInstance().findFileByPath(it)
            }
            FileChooser.chooseFile(descriptor, null, initial) { virtualFile ->
                textField.text = virtualFile.path
                writeSelectedRowIcon(textField.text)
            }
        }
        textField.addActionListener {
            if (toolsTable.selectedRow >= 0) writeSelectedRowIcon(textField.text)
        }
        toolsTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = toolsTable.selectedRow
            textField.text = if (row >= 0) (toolsModel.getValueAt(row, COL_ICON_PATH) as? String) ?: "" else ""
            syncEnabled()
        }
        syncEnabled()
        val p = JPanel(com.intellij.ui.components.panels.HorizontalLayout(6)).apply {
            add(JLabel(message("icon.label")))
            add(textField)
            add(browse)
            add(note)
        }
        return p
    }

    private fun writeSelectedRowIcon(path: String) {
        val row = toolsTable.selectedRow
        if (row < 0) return
        if (isLockedAgent(row)) {
            setStatus(message("status.cannotReIcon"), warn = true)
            return
        }
        toolsModel.setValueAt(path, row, COL_ICON_PATH)
    }

    /** 校验选中行（无选中则全表）：命令能否在 PATH 解析 + 图标（URL 则下载）能否就位。 */
    private fun validateButton(): JComponent {
        val button = JButton(message("button.validate"))
        button.toolTipText = message("button.validate.tooltip")
        button.addActionListener {
            val rows = if (toolsTable.selectedRow >= 0) listOf(toolsTable.selectedRow)
            else (0 until toolsModel.rowCount).toList()
            if (rows.isEmpty()) {
                setStatus(message("status.nothingToValidate"), warn = true)
                return@addActionListener
            }
            val entries = rows.mapNotNull { r ->
                val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
                val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
                val icon = (toolsModel.getValueAt(r, COL_ICON_PATH) as? String)?.trim() ?: ""
                if (id.isBlank() && command.isBlank()) null else RowInput(r, id, command, icon)
            }
            if (entries.isEmpty()) {
                setStatus(message("status.nothingToValidate"), warn = true)
                return@addActionListener
            }
            setStatus(message("status.validating"), warn = false)
            val app = ApplicationManager.getApplication()
            app.executeOnPooledThread {
                val results = entries.map { e ->
                    val cmd = CommandValidator.validate(e.command)
                    val icon = IconResolver.resolve(e.icon, e.id)
                    // 归档后路径会变成 <id>.svg/png，回填到表格里让用户看到最终位置
                    val archived = (icon as? IconResolver.Result.Local)
                        ?.path?.takeIf { it.isNotEmpty() && it != e.icon }
                    e to Triple(cmd, icon, archived)
                }
                app.invokeLater {
                    results.forEach { (e, triple) ->
                        val downloaded = triple.third
                        if (downloaded != null) toolsModel.setValueAt(downloaded, e.row, COL_ICON_PATH)
                    }
                    reportValidation(results)
                }
            }
        }
        return button
    }

    private fun reportValidation(results: List<Pair<RowInput, Triple<CommandValidator.Result, IconResolver.Result, String?>>>) {
        val lines = results.map { (e, triple) ->
            val (cmd, icon) = triple.first to triple.second
            val cmdText = when (cmd) {
                is CommandValidator.Result.Ok -> message("validation.command.ok")
                is CommandValidator.Result.NotFound -> message("validation.command.notFound", cmd.detail)
            }
            val iconText = when (icon) {
                is IconResolver.Result.Local ->
                    if (icon.path.isEmpty()) message("validation.icon.none") else message("validation.icon.ok")
                is IconResolver.Result.Error -> message("validation.icon.error", icon.message)
            }
            "· ${e.id.ifBlank { e.command }}: $cmdText; $iconText"
        }
        val allCmdOk = results.all { it.second.first is CommandValidator.Result.Ok }
        val allIconOk = results.all {
            it.second.second is IconResolver.Result.Local
        }
        val warn = !(allCmdOk && allIconOk)
        val prefix = message(if (warn) "status.validation.issues" else "status.validation.passed")
        setStatus(prefix + lines.first(), warn = warn)
        NOTIFY.createNotification(
            Yolo.NAME,
            lines.joinToString("<br>"),
            if (warn) NotificationType.WARNING else NotificationType.INFORMATION
        ).notify(null)
    }

    private fun setStatus(text: String, warn: Boolean) {
        statusLabel.text = text
        statusLabel.foreground = if (warn) JBColor.RED else JBColor.GREEN
    }

    // ── 重复检查 ───────────────────────────────────────────────────

    /**
     * 找出重复的 ID / Command。
     *
     * ID 直接按小写比；Command 按 [baseName] 后再比 —— 因为注入是按可执行文件名匹配的，
     * `/usr/local/bin/claude` 和 `claude.cmd` 实际会指向同一个 agent，属于真重复。
     * 空值不参与比较（新加的空行还没填完，不该立刻报错）。
     */
    private fun findDuplicates(): List<String> {
        val ids = HashMap<String, Int>()
        val cmds = HashMap<String, Int>()
        val dupIds = LinkedHashSet<String>()
        val dupCmds = LinkedHashSet<String>()
        for (r in 0 until toolsModel.rowCount) {
            val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
            val cmd = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            if (id.isNotEmpty() && ids.put(id.lowercase(), r) != null) dupIds += id
            if (cmd.isNotEmpty() && cmds.put(baseName(cmd).lowercase(), r) != null) dupCmds += cmd
        }
        val problems = mutableListOf<String>()
        if (dupIds.isNotEmpty()) problems += message("duplicate.id", dupIds.joinToString(", "))
        if (dupCmds.isNotEmpty()) problems += message("duplicate.command", dupCmds.joinToString(", "))
        return problems
    }

    /** 编辑过程中即时提示重复；无重复时清掉之前的重复告警。 */
    private fun reportDuplicates() {
        val problems = findDuplicates()
        if (problems.isNotEmpty()) {
            setStatus(problems.joinToString("; "), warn = true)
        } else if (statusLabel.foreground == JBColor.RED) {
            setStatus("", warn = false)
        }
    }

    /** 注册表格变更监听：标记“有改动”、实时查重、并按已知 agent 自动预填 Skip flag。
     *  单独抽出便于在无 UI 的测试环境里直接调用（createComponent 里的 ContextHelpLabel
     *  依赖 IDEA 的 CoroutineScope 服务，MockApplication 不提供）。 */
    private fun installListeners() {
        toolsModel.addTableModelListener { e ->
            if (rebuilding || autoFilling) return@addTableModelListener
            modified = true
            reportDuplicates()
            // 用户新增/编辑某行的 ID 或 Command 时，若该工具是已知 agent 且其 Skip flag 列还空着，
            // 用 DefaultSkipFlags 预填一个默认值——这样新加的 agent 不用手动抄写就“配好了 skip flag”。
            // env 型 agent（goose）不走这里，它的绕过由 TerminalSkipFlagCustomizer 里的 DefaultSkipEnvs 注入环境变量。
            if (e.type == TableModelEvent.UPDATE && (e.column == COL_ID || e.column == COL_COMMAND)) {
                autoFillSkipFlag(e.firstRow)
            }
        }
    }

    /** 用户新增/编辑某行 ID 或 Command 时，若该工具是已知 agent 且 Skip flag 列还空着，
     *  用 DefaultSkipFlags 预填默认值（按命令二进制名优先，回退到 ID）。
     *  只对“列仍为空”的情况填充，用户手动清空或改写的不会被覆盖。 */
    private fun autoFillSkipFlag(row: Int) {
        if (row < 0 || row >= toolsModel.rowCount) return
        val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.trim() ?: ""
        val command = (toolsModel.getValueAt(row, COL_COMMAND) as? String)?.trim() ?: ""
        val current = (toolsModel.getValueAt(row, COL_SKIP) as? String)?.trim() ?: ""
        if (current.isNotEmpty()) return
        val byCmd = if (command.isNotBlank()) DefaultSkipFlags.forId(baseName(command)) else ""
        val resolved = if (byCmd.isNotEmpty()) byCmd else DefaultSkipFlags.forId(id)
        if (resolved.isNotEmpty()) {
            autoFilling = true
            try {
                toolsModel.setValueAt(resolved, row, COL_SKIP)
            } finally {
                autoFilling = false
            }
        }
    }

    // ── 安装状态（图标点亮 / 变灰） ────────────────────────────────

    /** 后台探测每行 Command 是否在 PATH 上，得到“已安装”标记，驱动图标点亮 / 变灰。 */
    private fun refreshInstalledFlags() {
        val app = ApplicationManager.getApplication()
        val n = toolsModel.rowCount
        app.executeOnPooledThread {
            val flags = BooleanArray(n) { i ->
                val cmd = (toolsModel.getValueAt(i, COL_COMMAND) as? String)?.trim() ?: ""
                cmd.isNotEmpty() && AgentDetector.isOnPath(cmd)
            }
            app.invokeLater {
                toolsTable.setInstalled(flags)
            }
        }
    }

    // ── 持久化 ─────────────────────────────────────────────────────

    override fun isModified(): Boolean = modified

    override fun apply() {
        // 重复的 ID/Command 会让下拉出现两条同名项、且 skip 规则互相覆盖，直接拒绝保存
        findDuplicates().takeIf { it.isNotEmpty() }?.let {
            val text = it.joinToString("; ")
            setStatus(text, warn = true)
            throw ConfigurationException(text, Yolo.NAME)
        }

        val settings = AgentExtenderSettings.getInstance()

        // ① 权限规则：来自每一行的 Skip flag（按命令的二进制名匹配，customizer 才能命中）
        val newRules = mutableListOf<PermissionRule>()
        for (r in 0 until toolsModel.rowCount) {
            val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            val flag = (toolsModel.getValueAt(r, COL_SKIP) as? String)?.trim() ?: ""
            if (command.isNotBlank() && flag.isNotBlank()) {
                newRules.add(PermissionRule(agentId = baseName(command), flag = flag))
            }
        }
        settings.state.permissionRules = newRules

        // ② 自定义工具：跳过内置/推广（它们不写 customTools），只保存用户自定义工具
        val builtInCmds = BuiltInAgents.all().map { it.binaryName.lowercase() }.toSet()
        settings.state.customTools = (0 until toolsModel.rowCount).mapNotNull { r ->
            val id = (toolsModel.getValueAt(r, COL_ID) as? String)?.trim() ?: ""
            val command = (toolsModel.getValueAt(r, COL_COMMAND) as? String)?.trim() ?: ""
            if (id.isBlank() || command.isBlank()) null
            else if (id.lowercase() in builtInIds) null
            else if (command.lowercase() in builtInCmds) null
            else CustomTool(
                id = id,
                displayName = (toolsModel.getValueAt(r, COL_DISPLAY) as? String)?.trim() ?: id,
                command = command,
                baseArgs = (toolsModel.getValueAt(r, COL_BASE_ARGS) as? String) ?: "",
                iconPath = (toolsModel.getValueAt(r, COL_ICON_PATH) as? String)?.trim() ?: ""
            )
        }.toMutableList()

        AgentIcons.clearCache()

        modified = false

        ApplicationManager.getApplication().executeOnPooledThread {
            finalizeIcons(settings)
            reportMissingCommands(settings)
        }
    }

    private fun finalizeIcons(settings: AgentExtenderSettings) {
        var changed = false
        for (tool in settings.state.customTools) {
            val icon = tool.iconPath.trim()
            if (icon.isEmpty()) continue
            // URL 与本地文件都要归档成 <id>.svg/png；已归档的会被 IconResolver 原样返回
            when (val r = IconResolver.resolve(icon, tool.id)) {
                is IconResolver.Result.Local ->
                    if (r.path.isNotEmpty() && r.path != icon) { tool.iconPath = r.path; changed = true }
                is IconResolver.Result.Error ->
                    com.intellij.openapi.diagnostic.Logger.getInstance(AgentExtenderConfigurable::class.java)
                        .warn("AI Agents Extender: icon resolve failed ${r.message}")
            }
        }
        if (changed) {
            ApplicationManager.getApplication().invokeLater {
                AgentIcons.clearCache()
            }
        }
    }

    private fun reportMissingCommands(settings: AgentExtenderSettings) {
        val missing = settings.state.customTools.filter {
            CommandValidator.validate(it.command) is CommandValidator.Result.NotFound
        }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(", ") { it.displayName.ifBlank { it.id } }
            NOTIFY.createNotification(
                Yolo.NAME,
                message("notify.missingCommands", names),
                NotificationType.WARNING
            ).notify(null)
        }
    }

    override fun reset() {
        // 重建表格期间抑制 TableModelListener，避免把“加载”误判为“改动”
        rebuilding = true
        try {
            toolsModel.rowCount = 0
            val state = AgentExtenderSettings.getInstance().state
            // 已有权限规则按"命令二进制名"建索引，回填到每行的 Skip flag 列
            val ruleByCmd = state.permissionRules.associateBy({ baseName(it.agentId).lowercase() }, { it.flag })
            /** 优先用用户保存的规则；空则回退到 DefaultSkipFlags（按命令名再按 id）。 */
            fun flagFor(cmd: String, id: String = ""): String {
                val saved = ruleByCmd[baseName(cmd).lowercase()]
                if (!saved.isNullOrBlank()) return saved
                return DefaultSkipFlags.forId(baseName(cmd)).ifBlank {
                    DefaultSkipFlags.forId(id)
                }
            }

            // ① IDEA 内置（动态）
            val builtIns: List<TerminalAgent> = BuiltInAgents.all()
            builtInIds = builtIns.map { it.agentKey.key.lowercase() }.toSet()
            builtInIconById = builtIns.associate { it.agentKey.key.lowercase() to it.icon }
            val builtInCmds = builtIns.map { it.binaryName.lowercase() }.toSet()
            promotedIds = PromotedAgents.entries.map { it.id.lowercase() }.toSet()
            // IDEA 内置（动态）：逐行加入表格（保持 IDEA 给出的原生顺序，优先级最高）
            for (agent in builtIns) {
                val id = agent.agentKey.key
                toolsModel.addRow(
                    arrayOf<Any>(
                        "", id, agent.displayName, agent.binaryName,
                        "", flagFor(agent.binaryName, id), ""
                    )
                )
            }
            // ② 本插件推广（如 codebuddy）：按 ID 首字母排序，优先级低于内置、高于用户自定义
            for (meta in PromotedAgents.entries.sortedBy { it.id.lowercase() }) {
                toolsModel.addRow(
                    arrayOf<Any>(
                        "", meta.id, meta.displayName, meta.command,
                        "", flagFor(meta.command, meta.id), ""
                    )
                )
            }
            // ③ 用户自定义工具（跳过与内置/推广重复的 id 或 同命令的旧遗留条目）。
            //    按创建时间排序——用户每次新增都追加到列表末尾，没有行重排 UI，故保存顺序即创建顺序，
            //    直接按 state.customTools 的先后加入即可（优先级最低）。
            for (tool in state.customTools) {
                val lid = tool.id.lowercase()
                val cmd = tool.command.lowercase()
                if (lid in builtInIds || lid in promotedIds) continue
                if (cmd in builtInCmds) continue
                toolsModel.addRow(
                    arrayOf<Any>(
                        "", tool.id, tool.displayName, tool.command,
                        tool.baseArgs, flagFor(tool.command), tool.iconPath
                    )
                )
            }
            refreshInstalledFlags()
            setStatus("", warn = false)
        } finally {
            rebuilding = false
            modified = false
        }
    }

    // ── 渲染器 ─────────────────────────────────────────────────────

    /** 图标列：内置 agent 用 IDEA 自带图标；自定义工具用 AgentIcons（无则默认闪电）。
     *  未安装（installedFlags 为 false）时把图标渲染成灰色，已安装则正常点亮。 */
    private inner class IconRenderer : TableCellRenderer {
        private val label = JLabel()
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            label.icon = null
            label.text = ""
            if (table != null && row in 0 until toolsModel.rowCount) {
                val id = (toolsModel.getValueAt(row, COL_ID) as? String)?.lowercase() ?: ""
                val iconPath = (toolsModel.getValueAt(row, COL_ICON_PATH) as? String) ?: ""
                val baseIcon = builtInIconById[id] ?: AgentIcons.forAgent(id, iconPath)
                val installed = toolsTable.installedFlags.getOrElse(row) { false }
                label.icon = if (installed) baseIcon else greyed(baseIcon)
            }
            label.horizontalAlignment = SwingConstants.CENTER
            label.background = if (isSelected) table?.selectionBackground else table?.background
            label.foreground = if (isSelected) table?.selectionForeground else table?.foreground
            label.isOpaque = true
            return label
        }
    }

    /** 把图标画到 BufferedImage 上，用 GrayFilter 生成禁用（灰色）版本。 */
    private fun greyed(icon: Icon): Icon {
        if (icon.iconWidth <= 0 || icon.iconHeight <= 0) return icon
        val img = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        return ImageIcon(GrayFilter.createDisabledImage(img))
    }

    /** 合并列表表格：锁定 agent 的 ID/Command 只读；未安装的行整行变灰（不点亮）。 */
    private inner class AgentsTable(model: javax.swing.table.TableModel) : JBTable(model) {
        var installedFlags: BooleanArray = BooleanArray(0)

        fun setInstalled(flags: BooleanArray) {
            installedFlags = flags
            repaint()
        }

        override fun isCellEditable(row: Int, column: Int): Boolean {
            if (column == COL_ICON) return false
            if (column == COL_ID || column == COL_COMMAND) {
                val id = (model.getValueAt(row, COL_ID) as? String)?.trim()?.lowercase() ?: ""
                // 内置 / 推广 agent 的 ID / Command 固定，不可编辑
                if (id in builtInIds || id in promotedIds) return false
            }
            return super.isCellEditable(row, column)
        }

        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
            val c = super.prepareRenderer(renderer, row, column)
            if (isRowSelected(row)) return c
            val inst = installedFlags.getOrElse(row) { true }
            if (!inst && c is JComponent) {
                c.foreground = JBColor.GRAY
            }
            return c
        }
    }

    private data class RowInput(
        val row: Int,
        val id: String,
        val command: String,
        val icon: String
    )

    companion object {
        private const val COL_ICON = 0
        private const val COL_ID = 1
        private const val COL_DISPLAY = 2
        private const val COL_COMMAND = 3
        private const val COL_BASE_ARGS = 4
        private const val COL_SKIP = 5
        private const val COL_ICON_PATH = 6

        private val NOTIFY: com.intellij.notification.NotificationGroup by lazy {
            NotificationGroupManager.getInstance().getNotificationGroup("com.cnsharp.yolo.notifications")
        }
    }
}
