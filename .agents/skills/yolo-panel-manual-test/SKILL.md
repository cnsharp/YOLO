---
name: yolo-panel-manual-test
description: Use when manually testing or QA-ing the YOLO IntelliJ plugin panel — verifying the agent list, the YOLO skip-permissions toggle, the in-panel PTY terminal, and clickable link detection across any IntelliJ-series IDE or supported language.
---

# YOLO Panel Manual Test

Use this skill to verify the YOLO: AI Agents Extender panel in a real IDE. It is guidance, not a script: run the steps that match the change under test, and link `README.md` for feature detail. Treat each section as a checklist, not a required sequence.

## Scope and limitations

- This is manual QA of a built plugin, not an automated test. Run it against the plugin installed in a target IDE.
- Clickable-link checks only resolve files and classes inside the currently open project's content roots; tokens outside them never link. Always open the real project you intend to click into before pasting sample input.
- Settings changes require an IDE restart — a `Configurable` cannot hot-reload.

## Workflow

1. Build: `cd YOLO && ./gradlew buildPlugin` → `build/distributions/yolo-<ver>.zip`.
2. Install into the target IDE (Settings | Plugins | ⚙ | Install Plugin from Disk), restart.
3. Open a real project in that IDE (e.g. `cif` for Java/Kotlin) and open the YOLO tool window (right-side **y** icon; else View | Tool Windows | YOLO).
4. Walk §Features. For link checks, start any agent, paste the §Sample inputs into its terminal output, and click each entry to confirm navigation.

## Features to verify

- Panel lists only installed agents (promoted + custom); agents absent from `PATH` are hidden.
- Dropdown opens instantly from the cached scan; a background re-scan refreshes only when the installed set actually changes.
- YOLO (Skip Permissions) toggle defaults off and never auto-enables; when on, the next launch appends that agent's skip flag (claude→`--dangerously-skip-permissions`, codex→`--yolo`, codebuddy→`-y`, gemini→`--yolo`, …).
- Selecting a dropdown entry launches the agent in a real in-panel PTY terminal (JediTerm + PTY4J), not the IDEA Terminal API; its TUI renders.
- Ctrl+C passes through to the embedded terminal instead of opening IDEA's "Shortcuts conflicts" dialog.
- Terminal output becomes clickable and navigates; clicking hides the panel — except URLs, which open the browser and keep the panel open.
- Settings (gear): agent table, pre-filled skip flags, duplicate ID/command rejection (status line turns red, Apply refuses), Validate runs the command once and downloads its icon.

## Sample inputs

Paste into the agent terminal and click each to confirm navigation.

**File paths (line/column):** `/abs/path/to/File.kt:42`, `/abs/path/to/File.kt:42:13`, `C:\foo\Bar.kt:7`, `./Makefile:10`, `~/x/y.kt:3`, `file:///abs/x.kt`, `"/path with space/Bar.kt":5`, `path:12-18`, `Makefile`, `Dockerfile`.

**Bare stack frames:** `src/foo/Bar.kt:42`, `Bar.java:123`, `Bar.kt:12`, `File "app/main.py", line 42`, `at com.foo.Bar.method(Bar.java:123)`.

**Bare file names:** `plugin.xml`, `build.gradle.kts`, `README.md`.

**Type names:** `com.foo.Bar`, `com.foo.Bar.Baz` (inner class), `Bar` (project-local simple name).

**Member references (regression):** `CustomerException#getMessage` must land on **CustomerException** — `getMessage` is inherited from `Throwable`, so do not dive into the JDK; `CustomerException#getCode` must land precisely on the `getCode()` method declared in `CustomerException`; `com.foo.Bar.baz` / `Bar#findById` / `UserRepository.save` land on the member, or fall back to the referenced class when the member is inherited from a JDK/library class.

**URLs:** `https://example.com` opens the browser and keeps the panel open.

## Cross-IDE / cross-language coverage

Link detection is language-agnostic (extension/pattern based). Pick the sample for the project's language:

| Language | Sample reference |
|---|---|
| Java/Kotlin | `src/main/java/com/x/Main.java:10`, `Main.kt:20`, `com.x.Main` |
| Python | `app/main.py:42`, `File "app/main.py", line 42` |
| JS/TS | `src/index.ts:8`, `index.js:15` |
| Go | `cmd/main.go:30` |
| Rust | `src/main.rs:12` |
| C/C++ | `src/main.c:5`, `main.cpp:9` |
| Ruby | `lib/foo.rb:3` |
| PHP | `index.php:7` |

## Known pitfalls

- The IDE inherits a different `PATH` than your interactive shell → an agent is undetectable; use Settings → Validate to confirm.
- Links fail when the file is outside content roots, or (for type names) the Java module is disabled.
- Inherited-member fallback: a member declared in a JDK/library class (e.g. `CustomerException#getMessage`) navigates to the referenced class, never the JDK source.

## Pass criteria

Every feature in §Features holds, and at least one clicked link per §Sample inputs category navigates correctly (URLs keep the panel open). `CustomerException#getMessage` must land on `CustomerException`, never `Throwable`.
