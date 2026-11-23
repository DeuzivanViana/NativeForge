package io.nativeforge.runtime.template

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.feature.runner.LaunchResult
import io.nativeforge.feature.runner.LaunchSpecs
import io.nativeforge.feature.runner.ProgramLauncher
import io.nativeforge.feature.terminal.ConsoleContract
import io.nativeforge.feature.terminal.ConsoleRequest

/** Entry point of exported apps: reads the embedded launch spec and starts the right host. */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = LaunchSpecs.fromAssets(this)?.let { LaunchSpecs.resolvePaths(this, it) }
        when {
            spec == null -> toast("This app was not exported correctly: launch spec missing")
            spec.runtime == RuntimeKind.CONSOLE -> startActivity(
                ConsoleContract.intent(
                    this,
                    ConsoleRequest(
                        title = title.toString(),
                        workingDirectory = filesDir.path,
                        executable = spec.program,
                        arguments = spec.arguments,
                        environment = spec.environment + ("LD_LIBRARY_PATH" to applicationInfo.nativeLibraryDir),
                    ),
                ),
            )
            else -> (ProgramLauncher.launch(this, spec) as? LaunchResult.Unsupported)?.let { toast(it.reason) }
        }
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
