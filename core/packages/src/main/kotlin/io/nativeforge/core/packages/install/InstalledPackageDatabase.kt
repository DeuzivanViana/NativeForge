package io.nativeforge.core.packages.install

import io.nativeforge.core.packages.model.InstalledPackage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Records installed packages in a JSON file. Not thread-safe; callers serialize access.
 * The file is read again when it changed on disk: `pkg` in the terminal runs in another
 * process and writes the same file.
 */
class InstalledPackageDatabase(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(InstalledPackage.serializer())
    private var cache: MutableMap<String, InstalledPackage>? = null
    private var cacheStamp: Pair<Long, Long>? = null

    fun all(): List<InstalledPackage> = load().values.sortedBy { it.info.name }

    fun get(name: String): InstalledPackage? = load()[name]

    fun names(): Set<String> = load().keys

    fun put(pkg: InstalledPackage) {
        load()[pkg.info.name] = pkg
        persist()
    }

    fun remove(name: String) {
        load().remove(name)
        persist()
    }

    private fun stamp(): Pair<Long, Long> = file.lastModified() to file.length()

    private fun load(): MutableMap<String, InstalledPackage> = cache?.takeIf { cacheStamp == stamp() } ?: run {
        val loaded = if (file.isFile) {
            json.decodeFromString(serializer, file.readText()).associateByTo(LinkedHashMap()) { it.info.name }
        } else {
            LinkedHashMap()
        }
        cache = loaded
        cacheStamp = stamp()
        loaded
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(serializer, load().values.toList()))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        cacheStamp = stamp()
    }
}
