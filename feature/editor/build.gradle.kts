plugins {
    alias(libs.plugins.nativeforge.android.library)
    alias(libs.plugins.nativeforge.android.compose)
}

android {
    namespace = "io.nativeforge.feature.editor"
    // sora's Logger calls android.util.Log, which throws in plain JVM tests otherwise.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(projects.core.ui)
    api(projects.core.lsp)
    implementation(platform(libs.sora.editor.bom))
    api(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.gson)
}

// GrammarJoniTest reads the grammars from disk; rerun it when they change.
tasks.withType<Test>().configureEach { inputs.dir("src/main/assets/textmate").withPropertyName("textmateGrammars") }
