package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.platform.FakePlatformServices
import kmp.bootstrap.appkit.platform.PickedImage
import kmp.bootstrap.appkit.platform.openLink
import kmp.bootstrap.appkit.platform.shareOrCopy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformServicesTest {
    @Test fun openLinkAllowsOnlySafeSchemes() {
        val p = FakePlatformServices()
        assertTrue(p.openLink("https://example.com/x")); assertTrue(p.openLink("mailto:a@b.c")); assertTrue(p.openLink("TEL:+84123"))
        assertFalse(p.openLink("javascript:alert(1)")); assertFalse(p.openLink("file:///etc/passwd")); assertFalse(p.openLink("không phải link"))
        assertEquals(listOf("https://example.com/x", "mailto:a@b.c", "TEL:+84123"), p.openedUrls)
    }

    @Test fun shareFallsBackToCopyWhenTheSystemSheetIsUnavailable() {
        val ok = FakePlatformServices(shareWorks = true); assertTrue(ok.shareOrCopy("a")); assertEquals(listOf("a"), ok.shared); assertTrue(ok.copied.isEmpty())
        val no = FakePlatformServices(shareWorks = false); assertFalse(no.shareOrCopy("b")); assertEquals(listOf("b"), no.copied)
    }

    @Test fun pickImageReturnsWhatWasSetOnceThenNullLikeACancelledDialog() = runTest {
        val p = FakePlatformServices(nextImage = PickedImage(byteArrayOf(1), "a.png", "image/png"))
        assertEquals("a.png", p.pickImage()?.filename); assertNull(p.pickImage())
    }
}
