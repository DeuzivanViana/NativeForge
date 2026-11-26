package io.nativeforge.app.settings

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.nativeforge.app.MainActivity
import io.nativeforge.app.NativeForgeApplication
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.template.ProjectTemplates
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.rosemoe.sora.widget.CodeEditor as SoraCodeEditor

/** The whole app: Home › project › More › Settings › font size › Back, several times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FontSizeInAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var activity: MainActivity
    private val container get() = ApplicationProvider.getApplicationContext<NativeForgeApplication>().container
    private val savedSp get() = container.settings.settings.value.editor.fontSizeSp

    private fun settle() = repeat(10) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun editors(): List<SoraCodeEditor> {
        val found = ArrayList<SoraCodeEditor>()
        fun search(view: View) {
            if (view is SoraCodeEditor) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))
        }
        search(activity.window.decorView)
        return found.filter { it.isAttachedToWindow }
    }

    private fun renderedSp(): Float {
        val shown = editors()
        assertEquals(1, shown.size, "editors on screen")
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, activity.resources.displayMetrics)
        return shown.single().textSizePx / px
    }

    /** [stepMs]: how long the person waits between taps; shorter than the 700 ms screen fade means mid-animation. */
    private fun setInSettings(target: Int, stepMs: Long = 0) {
        fun step() = if (stepMs == 0L) settle() else { compose.mainClock.advanceTimeBy(stepMs); compose.mainClock.autoAdvance = false }
        compose.onNodeWithContentDescription("More").performClick()
        settle()
        compose.onNodeWithText("Settings").performClick()
        step()
        while (savedSp.toInt() != target) {
            val button = if (savedSp < target) "Larger" else "Smaller"
            compose.onAllNodesWithContentDescription(button).fetchSemanticsNodes().size.let { if (it == 0) error("no $button") }
            compose.onAllNodesWithContentDescription(button)[0].performScrollTo().performClick()
            step()
        }
        compose.onAllNodesWithContentDescription("Back").let { nodes ->
            // While Settings fades in, the editor's Back is still on screen too; press the last one (Settings).
            val count = nodes.fetchSemanticsNodes().size
            nodes[count - 1].performClick()
        }
        step()
        compose.mainClock.autoAdvance = true
        settle()
    }


    @Test
    fun `with animations turned off`() {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        android.provider.Settings.Global.putFloat(ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        `font size from Settings reaches the open project every time`()
    }

    @Test
    fun `font size from Settings reaches the open project every time`() {
        val created = runBlocking { container.projectRepository.create("demo", ProjectTemplates.all.first { it.id == "console" }) }
        assertTrue(created is Outcome.Success, "$created")
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity = it }
        settle()
        compose.onNodeWithText("demo").performClick()
        settle()
        assertEquals(13f, renderedSp(), 0.01f)

        for (size in listOf(10, 20, 15, 22, 11)) {
            setInSettings(size)
            assertEquals(size.toFloat(), savedSp, "saved")
            assertEquals(size.toFloat(), renderedSp(), 0.01f, "drawn after setting $size")
        }
    }
}
