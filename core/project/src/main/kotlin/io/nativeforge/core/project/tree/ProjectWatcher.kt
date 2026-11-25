package io.nativeforge.core.project.tree

import io.nativeforge.core.project.model.Project
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.concurrent.thread

/**
 * Tells the explorer when files appear or disappear in a project, for example after a
 * `git pull`, a command in the terminal or a Makefile writing its program, so it no longer
 * needs a manual refresh. Uses the platform's [WatchService], which is inotify on Android and
 * Linux: no polling, no wake-ups while nothing changes.
 *
 * Only folders and files that the explorer shows are watched: not `build/`, not the
 * [ProjectTree.HIDDEN_NAMES] folders, and at most [maxDirectories] folders.
 */
class ProjectWatcher(private val root: File, private val maxDirectories: Int = MAX_DIRECTORIES) {

    /** Emits once after each burst of files created or deleted, [quietMillis] after the last one. */
    @OptIn(FlowPreview::class)
    fun changes(quietMillis: Long = 400): Flow<Unit> = rawChanges().debounce(quietMillis)

    private fun rawChanges(): Flow<Unit> = callbackFlow {
        val rootPath = root.toPath()
        val service: WatchService = rootPath.fileSystem.newWatchService()
        val keys = HashMap<WatchKey, Path>()

        fun register(start: Path) {
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS) || isIgnored(rootPath, start)) return
            val pending = ArrayDeque(listOf(start))
            while (pending.isNotEmpty() && keys.size < maxDirectories) {
                val dir = pending.removeFirst()
                runCatching { keys[dir.register(service, ENTRY_CREATE, ENTRY_DELETE)] = dir }
                dir.toFile().listFiles().orEmpty()
                    .map { it.toPath() }
                    .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !isIgnored(rootPath, it) }
                    .forEach(pending::addLast)
            }
        }

        register(rootPath)
        val reader = thread(name = "project-watcher", isDaemon = true) {
            try {
                while (true) {
                    val key = service.take()
                    val dir = keys[key]
                    var changed = false
                    for (event in key.pollEvents()) {
                        if (event.kind() == OVERFLOW) { changed = true; continue }
                        val child = dir?.resolve(event.context() as Path) ?: continue
                        if (isIgnored(rootPath, child)) continue
                        changed = true
                        if (event.kind() == ENTRY_CREATE) register(child)
                    }
                    if (!key.reset()) keys.remove(key)
                    if (changed) trySend(Unit)
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
            }
        }
        awaitClose {
            runCatching { service.close() }
            reader.interrupt()
        }
    }

    companion object {
        const val MAX_DIRECTORIES = 512

        /** Suffix of the temporary file of an atomic save (see SafeFiles.writeTextAtomically). */
        const val SAVE_SUFFIX = ".nf-save"

        /** True for paths the explorer never shows, and for temporary files of a save. */
        fun isIgnored(root: Path, path: Path): Boolean {
            if (path == root) return false
            val relative = runCatching { root.relativize(path) }.getOrNull() ?: return true
            if (relative.toString().startsWith("..")) return true
            val parts = relative.map { it.toString() }
            if (parts.first() == Project.BUILD_DIR) return true
            if (parts.any { it in ProjectTree.HIDDEN_NAMES }) return true
            return parts.last().endsWith(SAVE_SUFFIX)
        }
    }
}
