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
 * Resolve the input for a custom tool's icon:
 *  - Empty string → no icon specified (handed up to fall back to bundled/default icon)
 *  - http(s) URL → download
 *  - Anything else → treated as a local file absolute path
 *
 * Regardless of source, the result is archived into the plugin's icon directory and named `<id>.svg` / `<id>.png`
 * by agent id, matching the naming rule of bundled icons. This way the icon stays usable even if the user's
 * original file is moved or deleted.
 *
 * When a same-named file already exists but its content differs, the old file is renamed by its creation time to
 * `<id>_<yyyyMMdd-HHmmss>.<ext>` as a backup, and is not overwritten and lost.
 *
 * The archive directory uses [PathManager.getConfigPath], which on Windows maps to
 * `%APPDATA%\JetBrains\<IDE>`, on macOS to `~/Library/Application Support/...`,
 * and on Linux to `~/.config/...`, naturally cross-platform and under user data (AppData).
 */
object IconResolver {

    private val ICON_DIR = Paths.get(PathManager.getConfigPath(), "yolo", "icons")

    private val BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    sealed interface Result {
        /** Local icon path that can be loaded directly (empty string means no icon specified). */
        data class Local(val path: String) : Result
        data class Error(val message: String) : Result
    }

    /**
     * @param input   The icon source entered by the user: http(s) URL, local absolute path, or empty string
     * @param agentId Used to name the archived file; if blank, falls back to hashing the source
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
            // Already a file in the archive directory (a product of a previous apply): reuse as-is, to avoid re-backing-up on every save
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

    /** Write the icon content to `<ICON_DIR>/<id>.<ext>`, backing up any same-named old file first if needed. */
    private fun archive(bytes: ByteArray, ext: String, agentId: String, source: String): Result {
        val dir = ICON_DIR.toFile()
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.Error(message("error.icon.dirCreate", dir.absolutePath))
        }

        val base = sanitize(agentId).ifBlank { source.hashCode().toString(36) }
        val target = File(dir, "$base.$ext")

        // If the content is exactly the same, no change is needed and no extra backup is produced
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
            // Only touch the old file after validation passes, to avoid a bad icon replacing a good one
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

    /** Back up the old icon with a creation-time suffix; append an index if overwritten again within the same second, to avoid clobbering each other. */
    private fun backup(target: File, base: String, ext: String) {
        val stamp = LocalDateTime.ofInstant(creationTime(target), ZoneId.systemDefault()).format(BACKUP_STAMP)
        var dest = File(target.parentFile, "${base}_$stamp.$ext")
        var n = 1
        while (dest.exists()) {
            dest = File(target.parentFile, "${base}_$stamp-$n.$ext")
            n++
        }
        if (!target.renameTo(dest)) {
            // Backup failure does not block the main flow: the icon itself can still update normally
            runCatching { target.copyTo(dest, overwrite = false) }
        }
    }

    /** Get the file's creation time; fall back to last-modified time when the platform does not support it. */
    private fun creationTime(file: File): java.time.Instant = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val created = attrs.creationTime().toInstant()
        if (created.toEpochMilli() > 0) created else java.time.Instant.ofEpochMilli(file.lastModified())
    } catch (_: Exception) {
        java.time.Instant.ofEpochMilli(file.lastModified())
    }

    private fun isArchived(file: File): Boolean =
        runCatching { file.canonicalFile.parentFile == ICON_DIR.toFile().canonicalFile }.getOrDefault(false)

    /** Only keep safe characters in the filename, to avoid spaces/slashes in the id breaking the path. */
    private fun sanitize(id: String): String =
        id.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_").trim('.', '_')

    /**
     * Sniff the type purely by content; only accept svg / png.
     *
     * Deliberately do not fall back to the extension: IconLoader.findIcon is lazy and does not actually decode
     * content, so a file whose content is text but whose suffix is .png can fool the check and get stored,
     * only failing at render time. Judging by content blocks it at the entry point.
     */
    private fun detectExtension(bytes: ByteArray): String? {
        if (bytes.size >= 8) {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.copyOfRange(0, 8).contentEquals(png)) return "png"
        }
        // Allow an XML declaration, DOCTYPE, or comment before <svg>
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
     * Verify the icon really works.
     *
     * IconLoader.findIcon only does lazy wrapping and does not decode content, so a corrupted image still returns
     * non-null; therefore PNG is additionally really decoded once via ImageIO to ensure it is not a truncated/corrupted file.
     */
    private fun isValidIcon(file: File, ext: String): Boolean = try {
        val loadable = IconLoader.findIcon(file.toURI().toURL()) != null
        loadable && (ext != "png" || javax.imageio.ImageIO.read(file) != null)
    } catch (_: Exception) {
        false
    }
}
