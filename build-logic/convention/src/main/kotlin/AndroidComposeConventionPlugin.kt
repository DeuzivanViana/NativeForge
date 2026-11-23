import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import io.nativeforge.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Enables Jetpack Compose. Apply after an application or library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android: CommonExtension<*, *, *, *, *, *> =
            extensions.findByType(ApplicationExtension::class.java)
                ?: extensions.getByType(LibraryExtension::class.java)
        android.buildFeatures.compose = true

        dependencies {
            val bom = libs.findLibrary("compose-bom").get()
            add("implementation", platform(bom))
            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("compose-material3").get())
            add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
        }
    }
}
