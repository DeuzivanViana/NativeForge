package io.nativeforge.core.export.bundle

import io.nativeforge.core.export.axml.ManifestPatch
import io.nativeforge.core.export.axml.ManifestPatcher

/**
 * Applies a [ManifestPatch] to the protocol buffer form of AndroidManifest.xml that app
 * bundles use (`base/manifest/AndroidManifest.xml`, aapt2's `XmlNode` message). It
 * follows the same rules as the binary [ManifestPatcher] used for APKs.
 *
 * Fields used (aapt2 Resources.proto): XmlNode.element = 1; XmlElement.name = 3,
 * attribute = 4, child = 5; XmlAttribute.namespace_uri = 1, name = 2, value = 3,
 * resource_id = 5, compiled_item = 6; Item.prim = 7; Primitive.int_decimal_value = 6.
 */
class ProtoManifestPatcher {

    fun patch(manifest: ByteArray, patch: ManifestPatch): ByteArray {
        val root = ProtoMessage.decode(manifest)
        require(root.message(NODE_ELEMENT)?.string(ELEMENT_NAME) == "manifest") { "Not a proto AndroidManifest.xml" }
        root.updateMessages(NODE_ELEMENT) { patchElement(it, patch) }
        return root.encode()
    }

    private fun patchElement(element: ProtoMessage, patch: ManifestPatch) {
        val elementName = element.string(ELEMENT_NAME)
        element.updateMessages(ELEMENT_ATTRIBUTE) { attribute ->
            val name = attribute.string(ATTR_NAME)
            val resourceId = attribute.varint(ATTR_RESOURCE_ID)?.toInt() ?: 0
            when {
                elementName == "manifest" && name == "package" && resourceId == 0 ->
                    patch.packageName?.let { setString(attribute, it) }
                resourceId == ManifestPatcher.ATTR_VERSION_CODE -> patch.versionCode?.let { setInt(attribute, it, it.toString()) }
                resourceId == ManifestPatcher.ATTR_VERSION_NAME -> patch.versionName?.let { setString(attribute, it) }
                resourceId == ManifestPatcher.ATTR_LABEL && elementName in LABELLED_ELEMENTS ->
                    patch.label?.let { setString(attribute, it) }
                resourceId == ManifestPatcher.ATTR_SCREEN_ORIENTATION && elementName == "activity" ->
                    patch.screenOrientation?.let { setInt(attribute, it, ORIENTATION_NAMES[it] ?: it.toString()) }
                else -> renamePackagePrefix(attribute, elementName, resourceId, patch)
            }
        }
        element.updateMessages(ELEMENT_CHILD) { child -> child.updateMessages(NODE_ELEMENT) { patchElement(it, patch) } }
    }

    private fun renamePackagePrefix(attribute: ProtoMessage, elementName: String?, resourceId: Int, patch: ManifestPatch) {
        val oldPackage = patch.oldPackageName ?: return
        val newPackage = patch.packageName ?: return
        // Only plain strings: references and numbers carry a compiled item.
        if (attribute.message(ATTR_COMPILED_ITEM) != null) return
        if (resourceId == ManifestPatcher.ATTR_NAME && elementName in COMPONENT_ELEMENTS) return
        val value = attribute.string(ATTR_VALUE) ?: return
        if (value == oldPackage || value.startsWith("$oldPackage.")) {
            setString(attribute, newPackage + value.removePrefix(oldPackage))
        }
    }

    private fun setString(attribute: ProtoMessage, value: String) {
        attribute.setString(ATTR_VALUE, value)
        attribute.remove(ATTR_COMPILED_ITEM)
    }

    private fun setInt(attribute: ProtoMessage, value: Int, text: String) {
        attribute.setString(ATTR_VALUE, text)
        val primitive = ProtoMessage().apply { setVarint(PRIMITIVE_INT_DECIMAL, value.toLong()) }
        attribute.setMessage(ATTR_COMPILED_ITEM, ProtoMessage().apply { setMessage(ITEM_PRIMITIVE, primitive) })
    }

    private companion object {
        const val NODE_ELEMENT = 1
        const val ELEMENT_NAME = 3
        const val ELEMENT_ATTRIBUTE = 4
        const val ELEMENT_CHILD = 5
        const val ATTR_NAME = 2
        const val ATTR_VALUE = 3
        const val ATTR_RESOURCE_ID = 5
        const val ATTR_COMPILED_ITEM = 6
        const val ITEM_PRIMITIVE = 7
        const val PRIMITIVE_INT_DECIMAL = 6

        val LABELLED_ELEMENTS = setOf("application", "activity", "activity-alias")
        val COMPONENT_ELEMENTS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")

        /** `android:screenOrientation` names, as aapt2 keeps them in the value string. */
        val ORIENTATION_NAMES = mapOf(
            -1 to "unspecified", 0 to "landscape", 1 to "portrait", 6 to "sensorLandscape",
            7 to "sensorPortrait", 10 to "fullSensor",
        )
    }
}
