package io.nativeforge.core.export

import io.nativeforge.core.project.model.ExportSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportValidatorTest {
    private val valid = ExportSettings(appName = "Game", packageId = "com.example.game")

    @Test
    fun `accepts dotted permission names`() {
        val settings = valid.copy(permissions = listOf("android.permission.CAMERA", "com.example.game.MY_PERMISSION"))
        assertTrue(ExportValidator.validate(settings).isEmpty())
    }

    @Test
    fun `rejects malformed permission names`() {
        val problems = ExportValidator.validate(valid.copy(permissions = listOf("CAMERA", "android.permission.CAMERA", "bad name.x")))
        assertEquals(listOf("\"CAMERA\" is not a valid permission name", "\"bad name.x\" is not a valid permission name"), problems)
    }
}
