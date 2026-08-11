package com.cnsharp.yolo

/**
 * Global, plugin-wide constants. Centralizing these avoids repeating literal strings (e.g. the "YOLO"
 * tool window id) across packages and keeps them in sync with plugin.xml.
 */
object YoloConstants {
    /** Tool window id and PTY process name. Must match <toolWindow id="YOLO"> in plugin.xml. */
    const val ID = "YOLO"
}
