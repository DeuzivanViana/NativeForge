package io.nativeforge.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.nativeforge.app.ui.export.ExportScreen
import io.nativeforge.app.ui.home.HomeScreen
import io.nativeforge.app.ui.packages.PackagesScreen
import io.nativeforge.app.ui.settings.SettingsScreen
import io.nativeforge.app.ui.workspace.WorkspaceScreen

/** Navigation routes. Add a screen by adding a route here and a `composable` entry below. */
object Routes {
    const val HOME = "home"
    const val PACKAGES = "packages"
    const val SETTINGS = "settings"
    const val WORKSPACE = "workspace/{project}"
    const val EXPORT = "export/{project}"

    fun workspace(project: String) = "workspace/${Uri.encode(project)}"
    fun export(project: String) = "export/${Uri.encode(project)}"
}

@Composable
fun NativeForgeNavHost() {
    val navController = rememberNavController()
    val projectArgument = listOf(navArgument("project") { type = NavType.StringType })

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenProject = { navController.navigate(Routes.workspace(it)) },
                onOpenPackages = { navController.navigate(Routes.PACKAGES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PACKAGES) {
            PackagesScreen(onBack = navController::popBackStack)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = navController::popBackStack)
        }
        composable(Routes.WORKSPACE, arguments = projectArgument) { entry ->
            val project = entry.arguments?.getString("project").orEmpty()
            WorkspaceScreen(
                projectName = project,
                onBack = { navController.popBackStack() },
                onOpenPackages = { navController.navigate(Routes.PACKAGES) },
                onExport = { navController.navigate(Routes.export(project)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.EXPORT, arguments = projectArgument) { entry ->
            ExportScreen(
                projectName = entry.arguments?.getString("project").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
