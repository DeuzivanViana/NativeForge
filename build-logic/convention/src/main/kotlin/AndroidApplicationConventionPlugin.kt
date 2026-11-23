import com.android.build.api.dsl.ApplicationExtension
import io.nativeforge.buildlogic.NativeForgeConfig
import io.nativeforge.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = NativeForgeConfig.IDE_TARGET_SDK
            lint {
                // Intentional: see NativeForgeConfig.IDE_TARGET_SDK.
                disable += "ExpiredTargetSdkVersion"
                disable += "OldTargetApi"
            }
            packaging {
                // Native libraries must be extracted to disk so they can be
                // executed or dlopen()ed by absolute path.
                jniLibs.useLegacyPackaging = true
            }
        }
    }
}
