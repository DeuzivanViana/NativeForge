package io.nativeforge.core.lsp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

/** Zero-based line and UTF-16 column, as in the LSP specification. */
@Serializable
data class Position(val line: Int, val character: Int)

@Serializable
data class Range(val start: Position, val end: Position)

@Serializable
data class Location(val uri: String, val range: Range) {
    val file: File? get() = DocumentUri.toFile(uri)
}

@Serializable
data class TextEdit(val range: Range, val newText: String)

enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }

data class LspDiagnostic(
    val range: Range,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String?,
    val code: String?,
)

/** Subset of LSP's CompletionItemKind used to pick icons. */
enum class CompletionKind {
    TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, CLASS, INTERFACE, MODULE, PROPERTY,
    UNIT, VALUE, ENUM, KEYWORD, SNIPPET, COLOR, FILE, REFERENCE, FOLDER, ENUM_MEMBER, CONSTANT,
    STRUCT, EVENT, OPERATOR, TYPE_PARAMETER;

    companion object {
        fun of(value: Int?): CompletionKind = entries.getOrElse((value ?: 1) - 1) { TEXT }
    }
}

data class CompletionItem(
    val label: String,
    val kind: CompletionKind,
    val detail: String?,
    val documentation: String?,
    val sortText: String?,
    val filterText: String?,
    /** The edit to apply; when null, [insertText] (or [label]) replaces the word before the caret. */
    val textEdit: TextEdit?,
    val insertText: String?,
    val additionalTextEdits: List<TextEdit>,
)

data class CompletionResult(val isIncomplete: Boolean, val items: List<CompletionItem>)

/** Edits per file, produced by rename. */
data class WorkspaceEdit(val changes: Map<String, List<TextEdit>>)

/** Converts between files and `file://` URIs the way language servers expect. */
object DocumentUri {
    fun of(file: File): String = file.absoluteFile.toURI().toString().let {
        // File.toURI() yields "file:/path"; servers expect "file:///path".
        if (it.startsWith("file:/") && !it.startsWith("file:///")) "file://" + it.removePrefix("file:") else it
    }

    fun toFile(uri: String): File? = runCatching { File(URI(uri)) }.getOrNull()
}

/** Decoding of server responses whose shape varies (unions in the LSP specification). */
internal object LspDecoding {

    fun locations(result: JsonElement): List<Location> = when (result) {
        is JsonNull -> emptyList()
        is JsonArray -> result.mapNotNull { location(it) }
        is JsonObject -> listOfNotNull(location(result))
        else -> emptyList()
    }

    private fun location(element: JsonElement): Location? {
        val obj = element as? JsonObject ?: return null
        // Location or LocationLink.
        val uri = (obj["uri"] ?: obj["targetUri"])?.jsonPrimitive?.contentOrNull ?: return null
        val range = obj["range"] ?: obj["targetSelectionRange"] ?: obj["targetRange"] ?: return null
        return Location(uri, LspJson.decodeFromJsonElement(range))
    }

    fun diagnostics(params: JsonElement?): Pair<String, List<LspDiagnostic>>? {
        val obj = params as? JsonObject ?: return null
        val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return null
        val list = (obj["diagnostics"] as? JsonArray).orEmpty().mapNotNull { element ->
            val d = element as? JsonObject ?: return@mapNotNull null
            LspDiagnostic(
                range = LspJson.decodeFromJsonElement(d["range"] ?: return@mapNotNull null),
                severity = when (d["severity"]?.jsonPrimitive?.intOrNull) {
                    2 -> DiagnosticSeverity.WARNING
                    3 -> DiagnosticSeverity.INFORMATION
                    4 -> DiagnosticSeverity.HINT
                    else -> DiagnosticSeverity.ERROR
                },
                message = d["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = d["source"]?.jsonPrimitive?.contentOrNull,
                code = (d["code"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        return uri to list
    }

    fun completion(result: JsonElement): CompletionResult = when (result) {
        is JsonArray -> CompletionResult(false, result.mapNotNull(::completionItem))
        is JsonObject -> CompletionResult(
            isIncomplete = result["isIncomplete"]?.jsonPrimitive?.contentOrNull == "true",
            items = (result["items"] as? JsonArray).orEmpty().mapNotNull(::completionItem),
        )
        else -> CompletionResult(false, emptyList())
    }

    private fun completionItem(element: JsonElement): CompletionItem? {
        val obj = element as? JsonObject ?: return null
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return null
        return CompletionItem(
            label = label.trim(),
            kind = CompletionKind.of(obj["kind"]?.jsonPrimitive?.intOrNull),
            detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
            documentation = markup(obj["documentation"]),
            sortText = obj["sortText"]?.jsonPrimitive?.contentOrNull,
            filterText = obj["filterText"]?.jsonPrimitive?.contentOrNull,
            textEdit = obj["textEdit"]?.let(::textEditOf),
            insertText = obj["insertText"]?.jsonPrimitive?.contentOrNull,
            additionalTextEdits = (obj["additionalTextEdits"] as? JsonArray).orEmpty().mapNotNull(::textEditOf),
        )
    }

    /** TextEdit or InsertReplaceEdit (the "replace" range is used). */
    private fun textEditOf(element: JsonElement): TextEdit? {
        val obj = element as? JsonObject ?: return null
        val text = obj["newText"]?.jsonPrimitive?.contentOrNull ?: return null
        val range = obj["range"] ?: obj["replace"] ?: return null
        return TextEdit(LspJson.decodeFromJsonElement(range), text)
    }

    fun textEdits(result: JsonElement): List<TextEdit> =
        (result as? JsonArray).orEmpty().mapNotNull(::textEditOf)

    fun workspaceEdit(result: JsonElement): WorkspaceEdit {
        val obj = result as? JsonObject ?: return WorkspaceEdit(emptyMap())
        val changes = LinkedHashMap<String, MutableList<TextEdit>>()
        (obj["changes"] as? JsonObject)?.forEach { (uri, edits) ->
            changes.getOrPut(uri) { mutableListOf() } += textEdits(edits)
        }
        (obj["documentChanges"] as? JsonArray)?.forEach { change ->
            val doc = change as? JsonObject ?: return@forEach
            val uri = doc["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull ?: return@forEach
            changes.getOrPut(uri) { mutableListOf() } += textEdits(doc["edits"] ?: JsonArray(emptyList()))
        }
        return WorkspaceEdit(changes)
    }

    fun hover(result: JsonElement): String? {
        val obj = result as? JsonObject ?: return null
        return markup(obj["contents"])
    }

    /** String, MarkupContent, MarkedString or an array of them, as plain text. */
    private fun markup(element: JsonElement?): String? = when (element) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { markup(it) }.joinToString("\n").ifBlank { null }
        is JsonObject -> element["value"]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
