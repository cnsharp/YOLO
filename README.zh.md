# YOLO: AI Agents Extender

一款 IntelliJ IDEA 插件，它将你自己的 CLI 工具追加进 Terminal 的 **AI Agents** 下拉菜单，
并让你通过一个开关以 **YOLO 模式** 启动其中任意一个——在命令中带上其权限绕过标志。

IDEA 自带的下拉菜单里只有 Junie、Claude Code 和 Codex。如果你常用的 Agent 不在其中，
它就不会出现在那里。而且无论你用哪个 Agent，你仍然需要确认它的每一个操作。
本插件解决的正是这两个问题。

---

## 功能特性

### 扩展 AI Agents 下拉菜单

![yolo-more-agents.png](screenshots/yolo-more-agents.png)

- 列出你**已安装**的 Agent——推荐的 Agent（Claude Code、Codex、CodeBuddy、ZCode……）以及你自己的自定义工具。
  在 `PATH` 上检测不到的 Agent 不会显示，因此列表始终与当前机器相关。
- 每一行显示 Agent 的图标、名称，以及其配置的跳过（skip）与恢复（resume）标志。
- 下拉列表从**已缓存的安装扫描结果**瞬间加载——复用上一次运行所做的检测，只有当已安装 Agent 的集合真正发生变化时，
  才在后台重新扫描刷新。
- 面板标题栏中提供两个开关——**YOLO（跳过权限）** 和 **Resume Session**——以及设置齿轮和一个 **Launch（启动）** 按钮。

### YOLO 模式

![yolo-mode.png](screenshots/yolo-mode.png)

Terminal 工具栏里有一个 **YOLO（跳过权限）** 开关，紧靠 AI Agents 下拉菜单左侧。
打开它，下一次启动的 Agent 就会在命令后追加其权限绕过标志——
Claude Code 是 `--dangerously-skip-permissions`，Codex 是 `--yolo`，Copilot 是 `--allow-all`，以此类推。

该标志是**每个 Agent 独立配置**的，且完全可定制。插件内置了 17 个常见 Agent 的正确标志并预填好，
但每个值都可编辑，运行时没有任何硬编码。少数 Agent（如 Goose）通过环境变量而非标志来绕过权限；
这些也都已处理，因为环境变量需要在进程启动前设置，而不是追加到命令行。

**该开关默认关闭，且永远不会自行打开。**

下拉菜单右侧的齿轮按钮可直接打开插件设置。

### 恢复会话

面板标题栏里的 **Resume Session** 开关。打开它，下一次启动 Agent 时就会在命令后追加其恢复标志——
Claude Code / CodeBuddy / Copilot / Goose / Hermes / Kimi / Pi 为 `-r`，
Codex / Cursor / TraeCode 为 `--resume`，Cline 为 `--taskId`——
于是 Agent 会继续上一次的会话，而不是从头开始。

该标志是**每个 Agent 独立配置**的，且完全可定制（见[配置](#配置)）。插件已为常见 Agent 预填好正确的恢复标志；
自定义工具则在 **恢复会话参数（Resume flag）** 列中自行填写——自定义工具没有内置的 `agents.json` 条目，
那一列正是你为它设置恢复参数的唯一途径。没有命令行恢复能力的 Agent（例如 Gemini / OpenCode / ZCode 用的是 TUI 的 `/resume`）
在开关打开时仍照常启动、不会注入任何标志。

**该开关默认关闭，且永远不会自行打开。**

### 运行在面板内（真实终端）

**先在下拉列表中选择一个 Agent，然后点击 Launch（启动）**——下拉只做选择；当你按下 **Launch** 时，Agent 在一个
**直接嵌入 YOLO 面板的、真实的、可交互的终端**中打开：一个真正的 PTY（通过 JediTerm + PTY4J，即 IDE 自带的那套终端模拟器），
原地渲染 Agent 的 TUI。提示符、编辑器以及你在 rc 中定义的 `PATH`（nvm / fnm / npm 全局 bin……）都能正常工作，
因为 Agent 运行在一个交互式登录 shell 中。

由于"选择"与"执行"分离，**跳过权限** / **Resume Session** 开关总是作用于下一次启动，且改动下拉列表绝不会杀掉正在运行的终端。
你上次启动过的 Agent 会被记住，并在下次打开面板时自动重新选中。

- Agent 启动时，**光标自动落到终端里**，你可以立刻开始输入。
- **Ctrl+C 不再被 IDEA 的复制快捷键劫持。** 终端获得焦点时，Ctrl+C 会直接穿透到嵌入式终端，
  而不是弹出 IDEA 的"快捷键冲突"对话框。它是否会中断正在运行的 Agent，取决于 Agent 自身的 TUI。

> 这完全基于**公开 API**：JediTerm 和 PTY4J 是随 IntelliJ 平台一同发布的第三方库（并非
> `@ApiStatus.Internal` / `@Experimental` 的 Terminal API），因此插件在 JetBrains 市场仍可发布。
> IDE 自带的 `ConsoleView` 是只读输出（没有交互式输入），所以一个真实的 Agent 只能靠嵌入真正的终端
> 才能活在面板里——而这正是本插件所做的。

### 可点击的终端输出

Agent 运行期间，它的输出会被扫描并识别为引用、转换成超链接。点击链接即可跳转到对应位置，并**自动隐藏 YOLO 面板**，
使其不再遮挡编辑器。

| 你打印…… | 变成指向……的链接 |
|---|---|
| `src/foo/Bar.kt:42`、`/abs/Bar.kt:42:13`、`C:\foo\Bar.kt:7` | 该文件对应行 / 列 |
| `./Makefile:10`、`~/x/y.kt:3`、`file:///abs/x.kt` | 该文件（支持家目录相对路径和 `file://` URI） |
| `path:12-18` | 行范围起点的文件 |
| `"/path with space/Bar.kt":5` | 含空格的带引号路径 |
| `Bar.java:123`、`Bar.kt:12` | 裸堆栈帧 |
| `File "app/main.py", line 42` | Python / JS 回溯帧 |
| `plugin.xml`、`build.gradle.kts`、`README.md` | 项目中任意位置的裸文件名 |
| `com.foo.Bar` / `Bar` | 类声明（限定名或项目内的简单名） |
| `Bar.method` / `Bar#method` | 具体的成员 / 字段 / 内部类 |
| `https://example.com` | 该 URL，在你的系统浏览器中打开（面板**不会**隐藏） |

- **行 / 列导航**对路径、堆栈帧和成员引用都有效。
- 支持**无扩展名文件**（`Makefile`、`Dockerfile`）和 **Windows 路径**。
- URL 是例外：点击它会打开浏览器，但保持面板打开。

> ### 警告——关于 YOLO 模式
>
> 让 AI Agent **不经确认**地运行命令、编辑文件，可能带来不可逆的改动、执行不受信任的代码，
> 或暴露你的系统。这些风险来自 Agent 本身以及那个绕过权限的标志。**本插件只是帮你翻开那个标志——它自身
> 不添加任何此类行为**，不会代表你执行任何操作，也不对 Agent 所做之事负责。请只在你信任的环境中开启 YOLO 模式。

---

## 环境要求

| | |
|---|---|
| IDE | IntelliJ IDEA **2026.1** 或更高版本（`since-build 261`） |
| 依赖插件 | Terminal（`org.jetbrains.plugins.terminal`）——默认已启用 |

Terminal AI Agents 下拉菜单在 2026.1 中才向第三方 Agent 开放；在更早的版本上，
本插件所依赖的扩展点并不存在。

## 构建

使用标准 Gradle 任务构建：

```bash
./gradlew buildPlugin
# → build/distributions/yolo-{version}.zip
```

**针对本地安装的 IDEA 构建**——默认情况下，插件编译所依赖的是 `gradle.properties` 中固定的 IntelliJ SDK。
如果你想改为针对机器上已安装的 IDE 构建，可在项目根目录创建一个 `local.properties` 文件，指向它的安装位置：

```properties
localIdeaPath=/Applications/IntelliJ IDEA.app
```

这会用你本地的 IDE 覆盖 SDK，在需要匹配特定 IDE 版本或测试与固定 SDK 存在差异的内部 API 时非常有用。

## 安装

**从磁盘安装**——构建或下载 `yolo-<version>.zip`（见[构建](#构建)），然后
`Settings | Plugins | ⚙ | Install Plugin from Disk…` 并选择该 zip。按提示重启。

### 发布版本

本插件大量依赖 IntelliJ **内部** API——它所接入的 Terminal AI Agents 扩展点被标记为 internal。
依赖内部 API 的插件会被 JetBrains 市场审核拒绝，因此**本插件未发布至市场**。

请从本仓库的 **Releases** 页面获取构建产物：下载 `yolo-<version>.zip`，
然后按上述方式通过 *Install Plugin from Disk…* 安装。

---

## 配置

**`Settings | Tools | YOLO: AI Agents Extender`**——或点击 Terminal 工具栏中的齿轮。

![yolo-settings.png](screenshots/yolo-settings.png)

所有配置都集中在一张表里。每一行是一个 Agent，且每行都带有自己的标志：

| 列 | 含义 |
|---|---|
| 图标 | IDEA 内置 Agent 的原生图标；自定义工具则为你提供的文件或默认闪电图标。命令不在 `PATH` 上时显示为灰色 |
| ID | 唯一标识符 |
| 显示名 | 在下拉菜单中显示的名称 |
| 命令 | 可执行文件名——必须在 `PATH` 上可解析 |
| 基础参数 | 始终传入的参数，以空格分隔 |
| 跳过标志 | 权限绕过参数，在 YOLO 开关打开时追加 |
| 恢复会话参数 | 恢复参数，在 Resume Session 开关打开时追加（如 `-r`、`--resume`） |

行分三种，按优先级从高到低排列：

1. **IDEA 内置 Agent**——只读，不可移除，保留 IDEA 的图标
2. **本插件已知的 Agent**（Gemini、Cline、CodeBuddy……）——只读，按 ID 排序
3. **你自己的自定义工具**——完全可编辑，按你创建的顺序排列

前两种 Agent 上只有跳过标志与恢复会话参数可编辑。这是有意为之：插件应当扩展下拉菜单，而不是接管它。

### 便捷功能

- **跳过标志与恢复参数自动预填。** 打开设置，已知 Agent 已经带上了正确的标志。在新建行中键入已知 ID 或命令时，它们会随输入自动补全。你手动设置的值永远不会被覆盖。
- **重复项在你输入时即被捕获。** 重复的 ID 或命令会立刻把状态行变红，且 Apply 会拒绝保存。命令按可执行文件名比较，因此 `/usr/bin/claude` 和 `claude.cmd` 算作同一个工具。
- **每次启动时检测已安装的 Agent。** 插件在后台检查每个已知 Agent 的命令——先查 `PATH`，再实际运行一次（`--version`）——然后把检测到的推荐 Agent 加入列表。这发生在**每次启动**，而不只是第一次，因此你之后安装的某个工具（例如通过 npm 安装的 Gemini）会自动出现。它**不会**自动发现你自己写的任意工具——那些请作为自定义工具添加。
- **校验（Validate）** 以相同方式（先查 PATH，再运行一次）检查某行的命令，并在有图标 URL 时下载该图标。

### 已知 Agent

下表中的标志已预填。它们全部可编辑，且此列表只是方便起见——并非唯一真相来源。最终运行的是设置里所写的内容。

| Agent | 命令 | 跳过标志 | 恢复会话参数 |
|---|---|---|---|
| Claude Code | `claude` | `--dangerously-skip-permissions` | `-r` |
| Codex | `codex` | `--yolo` | `--resume` |
| CodeBuddy | `codebuddy` | `-y` | `-r` |
| Gemini | `gemini` | `--yolo` | 无——仅 TUI `/resume` |
| Copilot | `copilot` | `--allow-all` | `-r` |
| Cursor | `cursor-agent` | `--force` | `--resume` |
| Kimi | `kimi` | `--yolo` | `-r` |
| Qoder | `qoder` | `--dangerously-skip-permissions` | 无——未实现 |
| Hermes | `hermes` | `--yolo` | `-r` |
| OpenCode | `opencode` | `--auto` | 无——仅 TUI `/resume` |
| Continue | `cn` | `--auto` | 无 |
| Cline | `cline` | `--auto-approve true` | `--taskId` |
| Goose | `goose` | 环境变量 `GOOSE_MODE=auto`——并非标志 | `-r` |
| Kilo Code | `kilo` | 无——仅 `kilo run` 接受 | 无 |
| OpenClaw | `openclaw` | 无——仅持久配置 | 无——`openclaw resume` 子命令 |
| Pi | `pi` | `--approve` | `-r` |
| TraeCode | `traecli` | 无 | `--resume` |
| ZCode | `zcode` | 无——无启动期跳过标志 | 无——仅 TUI `/resume` |

任何未列出的工具都可以作为自定义工具正常使用；只需自己填好它的标志即可。

---

## 故障排查

插件会记录它所启动的每一次运行。要查看实际运行了什么，可 grep IDE 日志
（`<version>` 为 IDE 的构建号，例如 `2026.2`）：

```bash
# macOS
grep "AI Agents Extender" ~/Library/Logs/JetBrains/IntelliJIdea<version>/idea.log

# Linux
grep "AI Agents Extender" ~/.cache/JetBrains/IntelliJIdea<version>/log/idea.log

# Windows (PowerShell)
grep "AI Agents Extender" "$env:LOCALAPPDATA\JetBrains\IntelliJIdea<version>\log\idea.log"
```

**某个 Agent 没有出现在下拉菜单中。** 它的命令在 `PATH` 上解析不到。查看设置表格——图标显示为灰色表示未找到。注意 IDE 继承的是启动它的那个进程的 `PATH`，可能与你的 Shell 环境不同。

**标志没有被应用。** 确认对应的开关已打开（跳过权限对应 YOLO 开关，恢复会话对应 Resume Session 开关），且该行在该列中配置了值。每次启动的日志行会显示最终命令，包括是否有内容被注入。

**设置改动不生效。** 插件注册了一个 `Configurable`，而 IDEA 无法动态加载它。安装或更新后请重启 IDE。
