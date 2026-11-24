package io.nativeforge.core.project.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPermissionTest {

    @Test
    fun `toggle adds and removes every name of a group`() {
        val custom = "android.permission.BLUETOOTH_CONNECT"
        val on = AppPermission.toggle(listOf(custom), AppPermission.INTERNET, enabled = true)
        assertEquals(listOf(custom, "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"), on)
        assertTrue(AppPermission.INTERNET.isIn(on))
        assertEquals(on, AppPermission.toggle(on, AppPermission.INTERNET, enabled = true), "no duplicates")

        val off = AppPermission.toggle(on, AppPermission.INTERNET, enabled = false)
        assertEquals(listOf(custom), off)
        assertFalse(AppPermission.INTERNET.isIn(off))
    }

    @Test
    fun `custom lists names no group covers`() {
        val current = listOf("android.permission.CAMERA", "android.permission.NFC")
        assertEquals(listOf("android.permission.NFC"), AppPermission.custom(current))
    }

    @Test
    fun `older project files without permissions still load`() {
        val json = """{"name":"game","export":{"appName":"Game","packageId":"com.example.game"}}"""
        val config = io.nativeforge.core.project.ProjectJson.decodeFromString(ProjectConfig.serializer(), json)
        assertEquals(emptyList(), config.export.permissions)
    }
}
