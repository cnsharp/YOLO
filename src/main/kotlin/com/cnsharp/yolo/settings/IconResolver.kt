package com.cnsharp.yolo.settings

import com.cnsharp.yolo.YoloBundle.message
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.IconLoader
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 解析自定义工具图标的输入：
 *  - 空字符串 → 不指定图标（交给上层回退到随包/默认图标）
 *  - http(s) URL → 下载
 *  - 其它 → 当作本地文件绝对路径
 *
 * 无论来源如何，最终都会归档到插件图标目录，并按 agent id 命名为 `<id>.svg` / `<id>.png`，
 * 与随包图标的命名规则保持一致。这样即使用户原始文件被移动或删除，图标依然可用。
 *
 * 同名文件已存在且内容不同时，旧文件会按其创建时间重命名为 `<id>_<yyyyMMdd-HHmmss>.<ext>` 备份，
 * 不会被直接覆盖丢失。
 *
 * 归档目录用 [PathManager.getConfigPath]，在 Windows 映射到
 * `%APPDATA%\JetBrains\<IDE>`，macOS 映射到 `~/Library/Application Support/...`，
 * Linux 映射到 `~/.config/...`，天然跨平台且落在用户数据（AppData）下。
 */
object IconResolver {

    private val ICON_DIR = Paths.get(PathManager.getConfigPath(), "yolo", "icons")

    private val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    sealed interface Result {
        /** 可直接用于加载的本地图标路径（空串表示未指定图标）。 */
        data class Local(val path: String) : Result
        data class Error(val message: String) : Result
    }

    /**
     * @param input   用户填写的图标来源：http(s) URL、本地绝对路径，或空串
     * @param agentId 用于命名归档文件；留空则退回按来源哈希命名
     */
    fun resolve(input: String, agentId: String = ""): Result {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.Local("")

        val bytes = if (isHttpUrl(raw)) {
            when (val r = download(raw)) {
                is Loaded.Ok -> r.bytes
                is Loaded.Fail -> return Result.Error(r.message)
            }
        } else {
            val file = File(raw)
            if (!file.isFile) return Result.Error(message("error.icon.notExist", raw))
            // 已经是归档目录里的文件（上一次 apply 的产物）：原样复用，避免每次保存都重复备份
            if (isArchived(file)) return Result.Local(file.absolutePath)
            try {
                file.readBytes()
            } catch (e: Exception) {
                return Result.Error(message("error.icon.unreadable", e.message ?: e.javaClass.simpleName))
            }
        }

        val ext = detectExtension(bytes)
            ?: return Result.Error(message("error.icon.unsupported", raw))

        return archive(bytes, ext, agentId, raw)
    }

    /** 把图标内容落到 `<ICON_DIR>/<id>.<ext>`，必要时先备份同名旧文件。 */
    private fun archive(bytes: ByteArray, ext: String, agentId: String, source: String): Result {
        val dir = ICON_DIR.toFile()
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.Error(message("error.icon.dirCreate", dir.absolutePath))
        }

        val base = sanitize(agentId).ifBlank { source.hashCode().toString(36) }
        val target = File(dir, "$base.$ext")

        // 内容完全相同则无需改动，也不产生多余备份
        if (target.isFile && target.readBytes().contentEquals(bytes)) {
            return Result.Local(target.absolutePath)
        }

        val tmp = File(dir, "$base.$ext.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!isValidIcon(tmp, ext)) {
                tmp.delete()
                return Result.Error(message("error.icon.invalid", source))
            }
            // 校验通过后才动旧文件，避免坏图标把好图标顶掉
            if (target.isFile) backup(target, base, ext)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return Result.Error(message("error.icon.write", target.absolutePath))
                }
            }
            return Result.Local(target.absolutePath)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return Result.Error(message("error.icon.save", e.message ?: e.javaClass.simpleName))
        }
    }

    /** 用创建时间给旧图标加后缀备份；同一秒内重复覆盖时再追加序号，避免互相顶掉。 */
    private fun backup(target: File, base: String, ext: String) {
        val stamp = LocalDateTime.ofInstant(creationTime(target), ZoneId.systemDefault()).format(BACKUP_STAMP)
        var dest = File(target.parentFile, "${base}_$stamp.$ext")
        var n = 1
        while (dest.exists()) {
            dest = File(target.parentFile, "${base}_$stamp-$n.$ext")
            n++
        }
        if (!target.renameTo(dest)) {
            // 备份失败不阻断主流程：图标本身还能正常更新
            runCatching { target.copyTo(dest, overwrite = false) }
        }
    }

    /** 取文件创建时间；平台不支持时回退到最后修改时间。 */
    private fun creationTime(file: File): java.time.Instant = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val created = attrs.creationTime().toInstant()
        if (created.toEpochMilli() > 0) created else java.time.Instant.ofEpochMilli(file.lastModified())
    } catch (_: Exception) {
        java.time.Instant.ofEpochMilli(file.lastModified())
    }

    private fun isArchived(file: File): Boolean =
        runCatching { file.canonicalFile.parentFile == ICON_DIR.toFile().canonicalFile }.getOrDefault(false)

    /** 文件名只保留安全字符，避免 id 里的空格/斜杠等破坏路径。 */
    private fun sanitize(id: String): String =
        id.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_").trim('.', '_')

    /**
     * 只按内容嗅探判断类型，只接受 svg / png。
     *
     * 刻意不回退到扩展名：IconLoader.findIcon 是惰性的，不会真正解码内容，
     * 所以一个内容是文本、后缀却是 .png 的文件能骗过校验被存进来，
     * 直到渲染时才失败。按内容判断可以在入口就挡掉。
     */
    private fun detectExtension(bytes: ByteArray): String? {
        if (bytes.size >= 8) {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.copyOfRange(0, 8).contentEquals(png)) return "png"
        }
        // 允许 <svg> 前存在 XML 声明、DOCTYPE 或注释
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8)
        if (head.contains("<svg", ignoreCase = true)) return "svg"
        return null
    }

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    private sealed interface Loaded {
        class Ok(val bytes: ByteArray) : Loaded
        class Fail(val message: String) : Loaded
    }

    private fun download(url: String): Loaded = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            Loaded.Fail(message("error.icon.downloadStatus", response.statusCode()))
        } else {
            Loaded.Ok(response.body())
        }
    } catch (e: Exception) {
        Loaded.Fail(message("error.icon.download", e.message ?: e.javaClass.simpleName))
    }

    /**
     * 校验图标真的能用。
     *
     * IconLoader.findIcon 只做惰性包装、不解码内容，损坏的图片也会返回非 null，
     * 所以 PNG 额外用 ImageIO 真正解一次码，确保不是截断/损坏的文件。
     */
    private fun isValidIcon(file: File, ext: String): Boolean = try {
        val loadable = IconLoader.findIcon(file.toURI().toURL()) != null
        loadable && (ext != "png" || javax.imageio.ImageIO.read(file) != null)
    } catch (_: Exception) {
        false
    }
}
