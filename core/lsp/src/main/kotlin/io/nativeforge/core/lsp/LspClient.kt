package io.nativeforge.core.lsp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Typed Language Server Protocol client on top of [JsonRpcConnection].
 *
 * Documents are synchronized with full-text updates: simpler than incremental
 * sync and cheap for source files of editor size.
 */
class LspClient(private val connection: JsonRpcConnection) {

    /** Diagnostics published by the server, per document URI. */
    @Volatile var onDiagnostics: (uri: String, diagnostics: List<LspDiagnostic>) -> Unit = { _, _ -> }

    /** Progress or log messages (`window/logMessage`), for troubleshooting. */
    @Volatile var onLog: (String) -> Unit = {}

    init {
        connection.onNotification = { method, params ->
            when (method) {
                "textDocument/publishDiagnostics" -> LspDecoding.diagnostics(params)?.let { (uri, list) -> onDiagnostics(uri, list) }
                "window/logMessage", "window/showMessage" ->
                    (params as? JsonObject)?.get("message")?.let { onLog(it.toString().trim('"')) }
            }
        }
        connection.onRequest = { method, _ ->
            when (method) {
                // clangd asks before showing indexing progress; accept and ignore it.
                "window/workDoneProgress/create" -> JsonNull
                "workspace/configuration" -> buildJsonArray { }
                else -> JsonNull
            }
        }
    }

    val isClosed: Boolean get() = connection.isClosed

    fun initialize(root: File, initializationOptions: JsonElement? = null): CompletableFuture<JsonElement> {
        val params = buildJsonObject {
            // Null: Android has no ProcessHandle, and the session stops the server itself.
            put("processId", JsonNull)
            put("rootUri", DocumentUri.of(root))
            putJsonArray("workspaceFolders") {
                addJsonObject {
                    put("uri", DocumentUri.of(root))
                    put("name", root.name)
                }
            }
            putJsonObject("capabilities") {
                putJsonObject("textDocument") {
                    putJsonObject("synchronization") { put("didSave", true) }
                    putJsonObject("completion") {
                        putJsonObject("completionItem") {
                            // Plain insert text: the editor has no snippet engine for LSP placeholders.
                            put("snippetSupport", false)
                            putJsonArray("documentationFormat") { add(kotlinx.serialization.json.JsonPrimitive("plaintext")) }
                        }
                        put("contextSupport", true)
                    }
                    putJsonObject("hover") {
                        putJsonArray("contentFormat") { add(kotlinx.serialization.json.JsonPrimitive("plaintext")) }
                    }
                    putJsonObject("publishDiagnostics") { put("relatedInformation", false) }
                    putJsonObject("definition") { put("linkSupport", false) }
                    putJsonObject("rename") { put("prepareSupport", false) }
                    putJsonObject("formatting") { }
                }
                putJsonObject("window") { put("workDoneProgress", false) }
                putJsonObject("general") { putJsonArray("positionEncodings") { add(kotlinx.serialization.json.JsonPrimitive("utf-16")) } }
            }
            if (initializationOptions != null) put("initializationOptions", initializationOptions)
        }
        return connection.request("initialize", params).thenApply { result ->
            connection.notify("initialized", buildJsonObject { })
            result
        }
    }

    fun didOpen(uri: String, languageId: String, version: Int, text: String) = connection.notify(
        "textDocument/didOpen",
        buildJsonObject {
            putJsonObject("textDocument") {
                put("uri", uri)
                put("languageId", languageId)
                put("version", version)
                put("text", text)
            }
        },
    )

    fun didChange(uri: String, version: Int, text: String) = connection.notify(
        "textDocument/didChange",
        buildJsonObject {
            putJsonObject("textDocument") {
                put("uri", uri)
                put("version", version)
            }
            putJsonArray("contentChanges") { addJsonObject { put("text", text) } }
        },
    )

    fun didSave(uri: String) = connection.notify("textDocument/didSave", documentParams(uri))

    fun didClose(uri: String) = connection.notify("textDocument/didClose", documentParams(uri))

    fun completion(uri: String, position: Position): CompletableFuture<CompletionResult> =
        connection.request("textDocument/completion", positionParams(uri, position)).thenApply(LspDecoding::completion)

    fun hover(uri: String, position: Position): CompletableFuture<String?> =
        connection.request("textDocument/hover", positionParams(uri, position)).thenApply(LspDecoding::hover)

    fun definition(uri: String, position: Position): CompletableFuture<List<Location>> =
        connection.request("textDocument/definition", positionParams(uri, position)).thenApply(LspDecoding::locations)

    fun declaration(uri: String, position: Position): CompletableFuture<List<Location>> =
        connection.request("textDocument/declaration", positionParams(uri, position)).thenApply(LspDecoding::locations)

    fun references(uri: String, position: Position): CompletableFuture<List<Location>> {
        val params = positionParams(uri, position).let { base ->
            JsonObject(base + ("context" to buildJsonObject { put("includeDeclaration", true) }))
        }
        return connection.request("textDocument/references", params).thenApply(LspDecoding::locations)
    }

    fun rename(uri: String, position: Position, newName: String): CompletableFuture<WorkspaceEdit> {
        val params = JsonObject(positionParams(uri, position) + ("newName" to kotlinx.serialization.json.JsonPrimitive(newName)))
        return connection.request("textDocument/rename", params).thenApply(LspDecoding::workspaceEdit)
    }

    fun formatting(uri: String, tabSize: Int, insertSpaces: Boolean = true): CompletableFuture<List<TextEdit>> {
        val params = buildJsonObject {
            putJsonObject("textDocument") { put("uri", uri) }
            putJsonObject("options") {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            }
        }
        return connection.request("textDocument/formatting", params).thenApply(LspDecoding::textEdits)
    }

    /** Drops a request whose answer is no longer needed. */
    fun <T> cancel(future: CompletableFuture<T>) {
        future.cancel(false)
    }

    /** Polite shutdown; the caller destroys the process if the server does not exit. */
    fun shutdown(timeoutMillis: Long = 1_500) {
        if (connection.isClosed) return
        runCatching {
            connection.request("shutdown").get(timeoutMillis, TimeUnit.MILLISECONDS)
            connection.notify("exit")
        }
        connection.close()
    }

    private fun documentParams(uri: String) = buildJsonObject { putJsonObject("textDocument") { put("uri", uri) } }

    private fun positionParams(uri: String, position: Position) = buildJsonObject {
        putJsonObject("textDocument") { put("uri", uri) }
        put("position", LspJson.encodeToJsonElement(position))
    }
}
