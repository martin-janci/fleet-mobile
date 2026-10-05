package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An upload without a 1024 px opaque icon is refused by App Store Connect. */
class IosAppIconTest {
    @Test
    fun the_catalog_names_the_image() {
        val contents = Repo.file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json").readText()
        assertTrue("\"filename\" : \"AppIcon-1024.png\"" in contents)
    }

    @Test
    fun the_image_is_1024_square_and_opaque() {
        val png = Repo.file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png").readBytes()
        assertEquals("PNG", String(png, 1, 3))
        fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (png[at + i].toInt() and 0xFF) }
        assertEquals(1024, int(16), "width")
        assertEquals(1024, int(20), "height")
        val colorType = png[25].toInt()
        assertTrue(colorType == 2 || colorType == 0, "PNG colour type $colorType carries alpha; App Store Connect refuses it")
    }
}
