package com.cnsharp.yolo

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.YoloBundle"

/**
 * 插件所有用户可见文案的入口。
 *
 * 默认（无后缀）资源包是英文；`YoloBundle_zh.properties` 提供中文。
 * 用 [DynamicBundle] 而不是普通 ResourceBundle：IDEA 的语言包插件（如中文语言包）
 * 切换 IDE 语言后，DynamicBundle 会跟着切，普通 ResourceBundle 只认 JVM 默认 locale。
 *
 * 缺失的 key 会回退到英文默认包，因此新增语言时不必一次翻译完。
 */
object YoloBundle : DynamicBundle(BUNDLE) {

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}

/** 产品名的唯一来源：改名只改 `plugin.name` 一处（plugin.xml 的 `<name>` 也从这里取）。 */
object Yolo {
    val NAME: String get() = YoloBundle.message("plugin.name")
}
