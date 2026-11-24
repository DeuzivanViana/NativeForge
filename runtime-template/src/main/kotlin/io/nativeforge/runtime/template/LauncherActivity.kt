package io.nativeforge.runtime.template

import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.feature.runner.BundledAssets
import io.nativeforge.feature.runner.LaunchResult
import io.nativeforge.feature.runner.LaunchSpecs
import io.nativeforge.feature.runner.ProgramLauncher
import io.nativeforge.feature.terminal.ConsoleContract
import io.nativeforge.feature.terminal.ConsoleRequest

/**
 * Entry point of exported apps: asks for the runtime permissions the export declared
 * (camera, microphone, location...), then reads the embedded launch spec and starts the right host.
 * Native code cannot easily ask for permissions itself, so they are requested up front.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change the earlier request is still on screen.
        if (savedInstanceState?.getBoolean(KEY_ASKING) == true) {
            asking = true
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            launch()
        } else {
            asking = true
            requestPermissions(missing, REQUEST_PERMISSIONS)
        }
    }

    /** True while the permission dialog is up (it survives a configuration change). */
    private var asking = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKING, asking)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The program starts either way; it sees a denied permission as a failing API call.
        if (requestCode == REQUEST_PERMISSIONS) {
            asking = false
            launch()
        }
    }

    /** Declared permissions not granted yet. Normal ones are granted at install, so these are the runtime ones. */
    private fun missingPermissions(): Array<String> {
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        }.getOrNull() ?: emptyArray<String>()
        return declared.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && isRuntime(it) }.toTypedArray()
    }

    @Suppress("DEPRECATION")
    private fun isRuntime(permission: String): Boolean = runCatching {
        val info = packageManager.getPermissionInfo(permission, 0)
        val protection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        protection == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private fun launch() {
        // Copying the project's assets can take a moment on first start: keep it off the main thread.
        Thread {
            val home = BundledAssets.prepare(this)
            runOnUiThread { start(home.path) }
        }.start()
    }

    private fun start(home: String) {
        if (isDestroyed) return
        val spec = LaunchSpecs.fromAssets(this)
            ?.let { LaunchSpecs.resolvePaths(this, it) }
            ?.let { it.copy(workingDirectory = it.workingDirectory ?: home) }
        when {
            spec == null -> toast("This app was not exported correctly: launch spec missing")
            spec.runtime == RuntimeKind.CONSOLE -> startActivity(
                ConsoleContract.intent(
                    this,
                    ConsoleRequest(
                        title = title.toString(),
                        workingDirectory = home,
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

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val KEY_ASKING = "asking_permissions"
    }
}
