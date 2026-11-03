package kmp.bootstrap.appkit

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class JwtTest {
    private fun b64(s: String, padded: Boolean = false) =
        (if (padded) Base64.UrlSafe else Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)).encode(s.encodeToByteArray())

    private fun jwt(payload: String, padded: Boolean = false) = "${b64("""{"alg":"HS256"}""")}.${b64(payload, padded)}.sig"

    @Test fun secondsLeftIsExpMinusNow() {
        assertEquals(500, Jwt.secondsLeft(jwt("""{"exp":1500}"""), 1000))
    }

    @Test fun anExpiredTokenGivesANegativeNumber() {
        assertEquals(-200, Jwt.secondsLeft(jwt("""{"exp":800}"""), 1000))
    }

    @Test fun bearerPrefixAndSurroundingSpacesAreIgnored() {
        assertEquals(60, Jwt.secondsLeft("Bearer  ${jwt("""{"exp":160}""")} ", 100))
    }

    @Test fun paddedAndUnpaddedBase64AreBothAccepted() {
        // Payload có độ dài khiến base64 có đệm `=` ở bản có đệm.
        val payload = """{"exp":2000,"sub":"ab"}"""
        assertEquals(1000, Jwt.secondsLeft(jwt(payload, padded = false), 1000))
        assertEquals(1000, Jwt.secondsLeft(jwt(payload, padded = true), 1000))
    }

    @Test fun urlSafeCharactersDecode() {
        // Chuỗi sinh ra ký tự `-` / `_` trong base64url (bytes 0xfb 0xff…) nằm trong một claim.
        val payload = """{"exp":900,"note":"???>>>~~~"}"""
        assertEquals(800, Jwt.secondsLeft(jwt(payload), 100))
    }

    @Test fun notAJwtGivesNull() {
        assertNull(Jwt.secondsLeft("", 0))
        assertNull(Jwt.secondsLeft("abc", 0))
        assertNull(Jwt.secondsLeft("a.b", 0))
        assertNull(Jwt.secondsLeft("a.b.c.d", 0))
    }

    @Test fun garbageInTheMiddleGivesNullInsteadOfThrowing() {
        assertNull(Jwt.secondsLeft("a.@@@.c", 0))
        assertNull(Jwt.secondsLeft("a.${b64("không phải json")}.c", 0))
        assertNull(Jwt.secondsLeft("a.${b64("[1,2]")}.c", 0))
    }

    @Test fun missingOrNonNumericExpGivesNull() {
        assertNull(Jwt.secondsLeft(jwt("""{"sub":"x"}"""), 0))
        assertNull(Jwt.secondsLeft(jwt("""{"exp":"soon"}"""), 0))
        assertNull(Jwt.secondsLeft(jwt("""{"exp":null}"""), 0))
    }
}
