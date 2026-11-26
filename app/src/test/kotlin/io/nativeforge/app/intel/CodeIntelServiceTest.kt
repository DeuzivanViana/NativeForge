package io.nativeforge.app.intel

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ClangToolchain
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Restarts after libraries were installed. No clangd is installed here, so a start ends at
 * once in [IntelStatus.Unavailable]: that shows whether a start happened.
 */
class CodeIntelServiceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val service = CodeIntelService(scope)
    private val root = Files.createTempDirectory("nf-intel").toFile()
    private val request = BuildRequest(
        project = Project(root, ProjectConfig(name = "demo")),
        toolchain = ClangToolchain(ToolchainLayout(root), Abi.ARM64_V8A),
    )

    @After fun tearDown() = scope.cancel()

    @Test
    fun `a restart on screen starts clangd right away`() {
        service.restart(request, CodeIntelOptions())
        assertIs<IntelStatus.Unavailable>(service.status.value)
    }

    @Test
    fun `a restart in the background waits until the workspace is shown again`() {
        service.restart(request, CodeIntelOptions())
        service.appHidden()

        // Libraries installed from the Packages screen while the workspace is behind it.
        service.restart(request, CodeIntelOptions())
        assertEquals(IntelStatus.Off, service.status.value)

        service.appShown()
        assertIs<IntelStatus.Unavailable>(service.status.value)
    }

    @Test
    fun `stopping clangd in the background is not undone when the workspace comes back`() {
        service.restart(request, CodeIntelOptions())
        service.appHidden()
        service.restart(request, CodeIntelOptions())
        // Code intelligence turned off in Settings.
        service.stop()

        service.appShown()
        assertEquals(IntelStatus.Off, service.status.value)
    }
}
