package io.nativeforge.core.export.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Values to change in a compiled AndroidManifest.xml. Null fields are left untouched. */
data class ManifestPatch(
    val packageName: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val label: String? = null,
    /** `screenOrientation` enum value for activities (see ExportSettings.Orientation.androidValue). */
    val screenOrientation: Int? = null,
    /**
     * Replaces this package prefix in string attributes such as permission names
     * and provider authorities, so two exported apps never clash on install.
     */
    val oldPackageName: String? = null,
    /** Permission names to declare as `<uses-permission>`; ones already declared are skipped. */
    val permissions: List<String> = emptyList(),
)

/**
 * Edits the binary XML (AXML) form of AndroidManifest.xml in place, without
 * re-compiling resources. This is what lets the IDE produce standalone APKs
 * on the device from a prebuilt template APK.
 */
class ManifestPatcher {

    fun patch(manifest: ByteArray, patch: ManifestPatch): ByteArray {
        val chunks = splitChunks(manifest)
        val poolIndex = chunks.indexOfFirst { it.type == Chunk.STRING_POOL }
        require(poolIndex >= 0) { "Binary XML has no string pool" }
        val pool = StringPool.decode(chunks[poolIndex].bytes)
        val resourceIds = chunks.firstOrNull { it.type == Chunk.XML_RESOURCE_MAP }?.let(::readResourceMap) ?: IntArray(0)

        chunks.filter { it.type == Chunk.XML_START_ELEMENT }.forEach { element ->
            patchElement(element.bytes, pool, resourceIds, patch)
        }

        val patchedChunks = chunks.mapIndexed { index, chunk -> if (index == poolIndex) pool.encode() else chunk.bytes }.toMutableList()
        val permissionChunks = permissionChunks(chunks, pool, resourceIds, patch.permissions)
        if (permissionChunks.isNotEmpty()) {
            // The pool may have grown, so it is encoded again after the new strings were added.
            patchedChunks[poolIndex] = pool.encode()
            val manifestStart = chunks.indexOfFirst { it.type == Chunk.XML_START_ELEMENT && elementName(it.bytes, pool) == "manifest" }
            require(manifestStart >= 0) { "Binary XML has no <manifest> element" }
            patchedChunks.addAll(manifestStart + 1, permissionChunks)
        }
        val body = ByteArrayOutputStream().apply { patchedChunks.forEach(::write) }.toByteArray()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Chunk.XML.toShort()).putShort(8).putInt(8 + body.size).array()
        return header + body
    }

    /**
     * Builds a start and an end chunk for each permission in [permissions] that the manifest
     * does not declare yet. They go right after `<manifest>`, as its first children.
     */
    private fun permissionChunks(chunks: List<RawChunk>, pool: StringPool, resourceIds: IntArray, permissions: List<String>): List<ByteArray> {
        val declared = chunks.filter { it.type == Chunk.XML_START_ELEMENT && elementName(it.bytes, pool) == USES_PERMISSION }
            .mapNotNull { stringAttribute(it.bytes, pool, resourceIds, ATTR_NAME) }
            .toSet()
        val missing = permissions.distinct().filter { it !in declared }
        if (missing.isEmpty()) return emptyList()

        // android:name must use the pool entry the resource map ties to the attribute id.
        val nameIndex = resourceIds.indexOf(ATTR_NAME)
        require(nameIndex >= 0) { "Binary manifest has no android:name attribute to reuse" }
        val namespace = pool.indexOf(ANDROID_NAMESPACE)
        val element = pool.indexOf(USES_PERMISSION)
        return missing.flatMap { permission ->
            val value = pool.indexOf(permission)
            val start = ByteBuffer.allocate(START_ELEMENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Chunk.XML_START_ELEMENT.toShort()).putShort(16).putInt(START_ELEMENT_SIZE)
                .putInt(0).putInt(-1) // line number, comment
                .putInt(-1).putInt(element) // namespace, name
                .putShort(20).putShort(20).putShort(1) // attribute start, size, count
                .putShort(0).putShort(0).putShort(0) // id, class and style attribute indices
                .putInt(namespace).putInt(nameIndex).putInt(value)
                .putShort(8).put(0).put(TYPE_STRING.toByte()).putInt(value)
                .array()
            val end = ByteBuffer.allocate(END_ELEMENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Chunk.XML_END_ELEMENT.toShort()).putShort(16).putInt(END_ELEMENT_SIZE)
                .putInt(0).putInt(-1).putInt(-1).putInt(element)
                .array()
            listOf(start, end)
        }
    }

    private fun elementName(bytes: ByteArray, pool: StringPool): String? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        return pool[buffer.getInt(headerSize + 4)]
    }

    private fun stringAttribute(bytes: ByteArray, pool: StringPool, resourceIds: IntArray, resourceId: Int): String? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        val attributeStart = buffer.getShort(headerSize + 8).toInt() and 0xFFFF
        val attributeSize = buffer.getShort(headerSize + 10).toInt() and 0xFFFF
        val attributeCount = buffer.getShort(headerSize + 12).toInt() and 0xFFFF
        return (0 until attributeCount).firstNotNullOfOrNull { i ->
            val attribute = Attribute(buffer, headerSize + attributeStart + i * attributeSize)
            if (resourceIds.getOrElse(attribute.nameIndex) { 0 } == resourceId && attribute.dataType == TYPE_STRING) {
                pool[attribute.rawValue]
            } else {
                null
            }
        }
    }

    private fun patchElement(bytes: ByteArray, pool: StringPool, resourceIds: IntArray, patch: ManifestPatch) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        val elementName = pool[buffer.getInt(headerSize + 4)]
        val attributeStart = buffer.getShort(headerSize + 8).toInt() and 0xFFFF
        val attributeSize = buffer.getShort(headerSize + 10).toInt() and 0xFFFF
        val attributeCount = buffer.getShort(headerSize + 12).toInt() and 0xFFFF

        for (i in 0 until attributeCount) {
            val offset = headerSize + attributeStart + i * attributeSize
            val attribute = Attribute(buffer, offset)
            val nameIndex = attribute.nameIndex
            val resourceId = resourceIds.getOrElse(nameIndex) { 0 }
            val attributeName = pool[nameIndex]

            when {
                elementName == "manifest" && attributeName == "package" && resourceId == 0 ->
                    patch.packageName?.let { attribute.setString(pool, it) }
                resourceId == ATTR_VERSION_CODE -> patch.versionCode?.let { attribute.setInt(it) }
                resourceId == ATTR_VERSION_NAME -> patch.versionName?.let { attribute.setString(pool, it) }
                resourceId == ATTR_LABEL && elementName in LABELLED_ELEMENTS ->
                    patch.label?.let { attribute.setString(pool, it) }
                resourceId == ATTR_SCREEN_ORIENTATION && elementName == "activity" ->
                    patch.screenOrientation?.let { attribute.setInt(it) }
                else -> renamePackagePrefix(attribute, pool, elementName, resourceId, patch)
            }
        }
    }

    private fun renamePackagePrefix(
        attribute: Attribute,
        pool: StringPool,
        elementName: String?,
        resourceId: Int,
        patch: ManifestPatch,
    ) {
        val oldPackage = patch.oldPackageName ?: return
        val newPackage = patch.packageName ?: return
        if (attribute.dataType != TYPE_STRING) return
        // Component class names must keep pointing at the real classes.
        if (resourceId == ATTR_NAME && elementName in COMPONENT_ELEMENTS) return
        val value = pool[attribute.rawValue] ?: return
        if (value == oldPackage || value.startsWith("$oldPackage.")) {
            attribute.setString(pool, newPackage + value.removePrefix(oldPackage))
        }
    }

    /** View over one `ResXMLTree_attribute` (20 bytes). */
    private class Attribute(private val buffer: ByteBuffer, private val offset: Int) {
        val nameIndex: Int get() = buffer.getInt(offset + 4)
        val rawValue: Int get() = buffer.getInt(offset + 8)
        val dataType: Int get() = buffer.get(offset + 15).toInt() and 0xFF

        fun setString(pool: StringPool, value: String) {
            val index = pool.indexOf(value)
            buffer.putInt(offset + 8, index)
            buffer.put(offset + 15, TYPE_STRING.toByte())
            buffer.putInt(offset + 16, index)
        }

        fun setInt(value: Int) {
            buffer.putInt(offset + 8, -1)
            buffer.put(offset + 15, TYPE_INT_DEC.toByte())
            buffer.putInt(offset + 16, value)
        }
    }

    private data class RawChunk(val type: Int, val bytes: ByteArray)

    private fun splitChunks(file: ByteArray): List<RawChunk> {
        val buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getShort(0).toInt() == Chunk.XML) { "Not a binary XML file" }
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        val chunks = mutableListOf<RawChunk>()
        var position = headerSize
        while (position + 8 <= file.size) {
            val type = buffer.getShort(position).toInt() and 0xFFFF
            val size = buffer.getInt(position + 4)
            require(size >= 8 && position + size <= file.size) { "Corrupt chunk at $position" }
            chunks += RawChunk(type, file.copyOfRange(position, position + size))
            position += size
        }
        return chunks
    }

    private fun readResourceMap(chunk: RawChunk): IntArray {
        val buffer = ByteBuffer.wrap(chunk.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getShort(2).toInt() and 0xFFFF
        return IntArray((chunk.bytes.size - headerSize) / 4) { buffer.getInt(headerSize + it * 4) }
    }

    companion object {
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10

        const val ATTR_LABEL = 0x01010001
        const val ATTR_NAME = 0x01010003
        const val ATTR_SCREEN_ORIENTATION = 0x0101001e
        const val ATTR_VERSION_CODE = 0x0101021b
        const val ATTR_VERSION_NAME = 0x0101021c

        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val USES_PERMISSION = "uses-permission"
        private const val START_ELEMENT_SIZE = 16 + 20 + 20
        private const val END_ELEMENT_SIZE = 16 + 8

        private val LABELLED_ELEMENTS = setOf("application", "activity", "activity-alias")
        private val COMPONENT_ELEMENTS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
    }
}
