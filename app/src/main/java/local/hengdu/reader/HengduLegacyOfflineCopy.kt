package local.hengdu.reader

import java.io.File
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** A read-only copy of one legacy chapter, kept separately from Mihon's downloads. */
internal object HengduLegacyOfflineCopy {
    private val pageName = Regex("^[0-9]{8}\\.(?:jpg|png|webp|gif|bmp|avif)$")
    private val versionName = Regex("^[0-9a-f-]{36}$")
    private val bookPath = Regex("^/manhua-[A-Za-z0-9_-]+/$")
    private val chapterPath = Regex("^/m[0-9]+/$")
    private const val MAX_PAGE = 32L * 1024 * 1024
    private const val MAX_CHAPTER = 500L * 1024 * 1024

    fun root(noBackupFilesDir: File): File = File(noBackupFilesDir, "hengdu-legacy-offline-v1")

    private fun validUrl(raw: String, path: Regex): Boolean = runCatching {
        val uri = URI(raw)
        raw.length <= 4096 && uri.scheme == "https" && uri.host == "www.manhuaren.com" &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.query == null &&
            uri.fragment == null && path.matches(uri.path)
    }.getOrDefault(false)

    private fun key(book: String, chapter: String): String = digest((book + "\u0000" + chapter).toByteArray())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), hash).use { input ->
            val buffer = ByteArray(16384)
            while (input.read(buffer) != -1) Unit
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Synchronized
    fun publish(root: File, staged: File, book: String, chapter: String): Boolean {
        require(validUrl(book, bookPath) && validUrl(chapter, chapterPath))
        if (resolve(root, book, chapter) != null) return true
        val source = staged.canonicalFile
        require(source.isDirectory)
        val files = source.listFiles()?.sortedBy { it.name } ?: error("無法讀取緩存圖片")
        require(files.size in 1..1000)
        var total = 0L
        files.forEachIndexed { index, file ->
            require(file.isFile && file.canonicalFile.parentFile == source && pageName.matches(file.name))
            require(file.name.substring(0, 8).toInt() == index + 1)
            require(file.length() in 1..MAX_PAGE)
            total += file.length()
            require(total <= MAX_CHAPTER)
        }
        val base = File(root, key(book, chapter))
        check(base.isDirectory || base.mkdirs())
        val version = File(base, UUID.randomUUID().toString())
        check(version.mkdir())
        try {
            val pages = File(version, "pages")
            check(pages.mkdir())
            val manifest = Properties().apply {
                setProperty("book", book)
                setProperty("chapter", chapter)
                setProperty("count", files.size.toString())
            }
            files.forEachIndexed { index, file ->
                val destination = File(pages, file.name)
                check(destination.createNewFile())
                file.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                val sourceHash = digest(file)
                check(file.length() == destination.length() && sourceHash == digest(destination))
                manifest.setProperty("page.$index", file.name)
                manifest.setProperty("size.$index", file.length().toString())
                manifest.setProperty("sha256.$index", sourceHash)
            }
            val pending = File(version, "manifest.tmp")
            check(pending.createNewFile())
            pending.outputStream().use { manifest.store(it, null) }
            check(pending.renameTo(File(version, "manifest.properties")))
            return true
        } catch (error: Throwable) {
            version.deleteRecursively()
            throw error
        }
    }

    fun resolve(root: File, book: String, chapter: String): File? {
        if (!validUrl(book, bookPath) || !validUrl(chapter, chapterPath)) return null
        val base = File(root, key(book, chapter))
        return base.listFiles().orEmpty().asSequence()
            .filter {
                runCatching {
                    it.isDirectory && versionName.matches(it.name) &&
                        it.canonicalFile.parentFile == base.canonicalFile
                }.getOrDefault(false)
            }
            .mapNotNull { version ->
                runCatching {
                    val properties = Properties()
                    File(version, "manifest.properties").inputStream().use(properties::load)
                    require(properties.getProperty("book") == book && properties.getProperty("chapter") == chapter)
                    val count = properties.getProperty("count").toInt()
                    require(count in 1..1000)
                    val pages = File(version, "pages").canonicalFile
                    require(pages.parentFile == version.canonicalFile)
                    val files = pages.listFiles()?.sortedBy { it.name } ?: error("無法讀取緩存圖片")
                    require(files.size == count)
                    var total = 0L
                    files.forEachIndexed { index, file ->
                        require(file.isFile && file.canonicalFile.parentFile == pages && pageName.matches(file.name))
                        require(
                            file.name.substring(0, 8).toInt() == index + 1 &&
                                properties.getProperty("page.$index") == file.name,
                        )
                        val size = properties.getProperty("size.$index").toLong()
                        require(size in 1..MAX_PAGE && file.length() == size)
                        total += size
                        require(total <= MAX_CHAPTER && properties.getProperty("sha256.$index") == digest(file))
                    }
                    pages
                }.getOrNull()
            }.firstOrNull()
    }
}
