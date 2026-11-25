package io.nativeforge.core.project.tree

import io.nativeforge.core.project.model.Project
import java.io.File
import java.nio.file.Files

/** One row of the explorer: a file or folder, its depth and its path relative to the project. */
data class FileNode(val file: File, val depth: Int, val isDirectory: Boolean, val relativePath: String)

/**
 * Lists a project's files for the explorer, folders first, in depth-first order.
 *
 * The explorer only reads the folders that are open ([list] with the expanded folders): a
 * project with a vendored library or thousands of assets opens without walking all of
 * them. [listAll] walks everything, for "Go to file". Symbolic links are listed but never
 * followed (a link could loop or leave the project).
 */
object ProjectTree {

    /** Never shown: version control and editor folders, and macOS litter. */
    val HIDDEN_NAMES = setOf(".git", ".cache", ".gradle", ".idea", ".vscode", ".DS_Store")

    const val MAX_NODES = 5000

    /** The root's entries, and the entries of every folder in [expanded] whose parents are expanded too. */
    fun list(root: File, expanded: Set<String>, maxNodes: Int = MAX_NODES): List<FileNode> =
        walk(root, maxNodes) { it.relativePath in expanded }

    /** Every file and folder (up to [maxNodes]). */
    fun listAll(root: File, maxNodes: Int = MAX_NODES): List<FileNode> = walk(root, maxNodes) { true }

    private fun walk(root: File, maxNodes: Int, descend: (FileNode) -> Boolean): List<FileNode> {
        val result = mutableListOf<FileNode>()
        fun visit(dir: File, depth: Int, prefix: String) {
            if (result.size >= maxNodes) return
            dir.listFiles().orEmpty()
                .filterNot { it.name in HIDDEN_NAMES || (depth == 0 && it.name == Project.BUILD_DIR) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { child ->
                    if (result.size >= maxNodes) return
                    val link = Files.isSymbolicLink(child.toPath())
                    val isDir = child.isDirectory && !link
                    val node = FileNode(child, depth, isDir, prefix + child.name)
                    result += node
                    if (isDir && descend(node)) visit(child, depth + 1, "${node.relativePath}/")
                }
        }
        visit(root, 0, "")
        return result
    }
}
