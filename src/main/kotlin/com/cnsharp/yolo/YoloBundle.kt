package com.cnsharp.yolo

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.YoloBundle"

/**
 * Entry point for all user-visible plugin text.
 *
 * The default (no-suffix) resource bundle is English; `YoloBundle_zh.properties` provides Chinese.
 * Uses [DynamicBundle] rather than a plain ResourceBundle: after switching the IDE language via IDEA's
 * language pack plugin (e.g. the Chinese pack), DynamicBundle follows, whereas a plain ResourceBundle only
 * honors the JVM default locale.
 *
 * Missing keys fall back to the English default bundle, so new languages need not be fully translated at once.
 */
object YoloBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}

/** Single source of truth for the product name: to rename, change only `plugin.name` (plugin.xml's `<name>` is also taken from here). */
object Yolo {
    val NAME: String get() = YoloBundle.message("plugin.name")
}
