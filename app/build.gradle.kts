import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.nativeforge.android.application)
    alias(libs.plugins.nativeforge.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.nativeforge.app"
    // Robolectric tests (the workspace and Settings screens) need the app's resources.
    testOptions.unitTests.isIncludeAndroidResources = true

    defaultConfig {
        applicationId = "io.github.deuzivanviana.nativeforge"
        versionCode = 21
        versionName = "0.12.0"

        // URL of the Android-targeted library index (see packages/README.md).
        // Override with -Pnativeforge.packageIndexUrl=... ; empty disables that source.
        val packageIndexUrl = providers.gradleProperty("nativeforge.packageIndexUrl").getOrElse("")
        buildConfigField("String", "PACKAGE_INDEX_URL", "\"$packageIndexUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Shrinks the libraries' unused code (Kotlin, AndroidX, Compose, Material icons);
            // the IDE's own classes and reflective libraries are kept (proguard-rules.pro).
            isMinifyEnabled = true
            // No resource shrinking: SDL and other hosts look resources up by name at run time.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

/**
 * Puts the runtime template (used by "Export") into a generated assets directory:
 * `assets/runtime/template.apk`, and `assets/runtime/bundle-skeleton.zip`, the template's
 * app bundle without `base/dex/` and `base/lib/`. Those are byte for byte the same as in
 * the APK, so the exporter takes them from there and the IDE carries them only once.
 */
abstract class CopyRuntimeTemplateTask : DefaultTask() {
    @get:InputFiles
    abstract val templateApkDirectory: ConfigurableFileCollection

    @get:InputFiles
    abstract val templateBundleDirectory: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val apk = templateApkDirectory.asFileTree.matching { include("*.apk") }.singleFile
        val bundle = templateBundleDirectory.asFileTree.matching { include("*.aab") }.singleFile
        val target = outputDirectory.get().asFile.resolve("runtime").apply { deleteRecursively(); mkdirs() }
        apk.copyTo(target.resolve("template.apk"), overwrite = true)
        ZipFile(bundle).use { source ->
            ZipOutputStream(target.resolve("bundle-skeleton.zip").outputStream().buffered()).use { out ->
                source.entries().asSequence()
                    .filterNot { it.isDirectory || it.name.startsWith("base/dex/") || it.name.startsWith("base/lib/") || it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
        }
    }
}

val copyRuntimeTemplate = tasks.register<CopyRuntimeTemplateTask>("copyRuntimeTemplate") {
    dependsOn(":runtime-template:assembleRelease", ":runtime-template:bundleRelease")
    templateApkDirectory.from(project(":runtime-template").layout.buildDirectory.dir("outputs/apk/release"))
    templateBundleDirectory.from(project(":runtime-template").layout.buildDirectory.dir("outputs/bundle/release"))
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyRuntimeTemplate, CopyRuntimeTemplateTask::outputDirectory)
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.project)
    implementation(projects.core.toolchain)
    implementation(projects.core.build)
    implementation(projects.core.packages)
    implementation(projects.core.export)
    implementation(projects.core.vcs)
    implementation(projects.core.lsp)
    implementation(projects.core.codeintel)
    implementation(projects.core.assets)
    implementation(projects.core.remote)
    implementation(projects.core.ui)
    implementation(projects.feature.editor)
    implementation(projects.feature.terminal)
    implementation(projects.feature.runner)
    implementation(projects.feature.assets)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // QR code of the remote access address (Apache-2.0, pure Java).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
}
