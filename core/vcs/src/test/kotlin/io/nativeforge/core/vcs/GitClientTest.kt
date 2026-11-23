package io.nativeforge.core.vcs

import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitClientTest {

    private val root: File = Files.createTempDirectory("nf-git").toFile()
    private val client = GitClient(File(root, "home"))
    private val me = GitIdentity("Tester", "tester@example.com")

    private fun upstream(): File {
        // Created with stock JGit so the link is stored as a real symlink entry.
        val dir = File(root, "upstream")
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            File(dir, "src").mkdirs()
            File(dir, "src/main.cpp").writeText("int main() { return 0; }\n")
            File(dir, "CMakeLists.txt").writeText("project(demo)\n")
            Files.createSymbolicLink(File(dir, "escape").toPath(), File("/etc/hostname").toPath())
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial commit").setAuthor("T", "t@example.com").setCommitter("T", "t@example.com").setSign(false).call()
            val mode = git.repository.newObjectReader().use { reader ->
                org.eclipse.jgit.treewalk.TreeWalk.forPath(reader, "escape", git.repository.resolve("HEAD^{tree}"))!!.getFileMode(0)
            }
            assertEquals(org.eclipse.jgit.lib.FileMode.SYMLINK, mode)
        }
        return dir
    }

    @Test
    fun `clone checks out files and turns symbolic links into plain files`() {
        val source = upstream()
        val target = File(root, "workspace/demo")
        client.cloneFrom(source.toURI().toString(), target, null, null, {}, { false })

        assertEquals("int main() { return 0; }\n", File(target, "src/main.cpp").readText())
        val link = File(target, "escape")
        assertFalse(Files.isSymbolicLink(link.toPath()), "symlinks are never created")
        assertEquals("/etc/hostname", link.readText())
        val status = client.status(target)
        assertEquals("main", status.branch)
        assertFalse(status.hasChanges)
        assertTrue(File(target, ".git/info/exclude").readText().contains("nativeforge.json"))
    }

    @Test
    fun `status diff commit and discard`() {
        val dir = upstream()
        File(dir, "src/main.cpp").writeText("int main() { return 1; }\n")
        File(dir, "src/new.cpp").writeText("void f() {}\n")
        File(dir, "CMakeLists.txt").delete()

        val status = client.status(dir)
        assertEquals(FileChange.MODIFIED, status.changes["src/main.cpp"])
        assertEquals(FileChange.UNTRACKED, status.changes["src/new.cpp"])
        assertEquals(FileChange.DELETED, status.changes["CMakeLists.txt"])

        val diff = client.diff(dir, "src/main.cpp")
        assertTrue("-int main() { return 0; }" in diff && "+int main() { return 1; }" in diff, diff)
        assertTrue("+void f() {}" in client.diff(dir, "src/new.cpp"), "untracked files diff as added")

        client.discard(dir, "src/main.cpp")
        client.discard(dir, "src/new.cpp")
        assertEquals("int main() { return 0; }\n", File(dir, "src/main.cpp").readText())
        assertFalse(File(dir, "src/new.cpp").exists())

        val commit = client.commitAll(dir, "Remove CMake", me)
        assertEquals("Remove CMake", commit.message)
        assertFalse(client.status(dir).hasChanges)
        assertEquals(listOf("Remove CMake", "Initial commit"), client.log(dir).map { it.message })
    }

    @Test
    fun `hooks in a repository never run`() {
        val dir = upstream()
        val marker = File(root, "hook-ran")
        File(dir, ".git/hooks").mkdirs()
        listOf("pre-commit", "commit-msg", "post-commit").forEach {
            File(dir, ".git/hooks/$it").apply { writeText("#!/bin/sh\ntouch ${marker.path}\n"); setExecutable(true) }
        }
        File(dir, "README.md").writeText("hi\n")
        client.commitAll(dir, "Add readme", me)
        assertFalse(marker.exists())
    }

    @Test
    fun `only https addresses are accepted`() {
        assertNull(GitUrls.validate("https://github.com/raysan5/raylib.git"))
        assertNotNull(GitUrls.validate("http://example.com/repo.git"))
        assertNotNull(GitUrls.validate("file:///data/data/io.github.deuzivanviana.nativeforge/files"))
        assertNotNull(GitUrls.validate("git@github.com:user/repo.git"))
        assertNotNull(GitUrls.validate("https://user:token@github.com/user/repo.git"))
        assertNotNull(GitUrls.validate("/sdcard/repo"))
        assertFailsWith<GitException> { client.clone("ext::sh -c touch% /tmp/pwned", File(root, "x")) }
        assertEquals("my-game", GitUrls.projectName("https://github.com/me/my-game.git"))
        assertEquals("raylib", GitUrls.projectName("https://github.com/raysan5/raylib/"))
    }

    @Test
    fun `a new repository is created with a gitignore`() {
        val dir = File(root, "fresh").apply { mkdirs() }
        File(dir, "main.c").writeText("int main(){}\n")
        client.init(dir)
        assertTrue(client.isRepository(dir))
        val status = client.status(dir)
        assertTrue(status.isEmpty)
        assertEquals(FileChange.UNTRACKED, status.changes["main.c"])
        assertEquals("build/\n", File(dir, ".gitignore").readText())
        assertTrue(Git.open(dir).use { it.repository.branch } == "main")
    }
}
