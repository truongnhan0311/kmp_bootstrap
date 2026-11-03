package kmp.bootstrap.appkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointsTest {
    @Test fun presetsBuildEveryPathFromOnePrefix() {
        val v2 = Endpoints.taper("/api/v2")
        assertEquals("/api/v2/auth/login", v2.login); assertEquals("/api/v2/auth/refresh", v2.refresh); assertEquals("/api/v2/me/devices", v2.devices)
        assertEquals("/api/v3/auth/login", Endpoints.taper("/api/v3").login, "đổi phiên bản API ở một chỗ")
        val flat = Endpoints.flat()
        assertEquals("/api/refresh", flat.refresh); assertEquals("/push", flat.devices)
        assertEquals("/v9/login", Endpoints.flat("/v9").login)
    }

    @Test fun contractsReadPathsFromEndpointsAndOwnNoneOfTheirOwn() {
        val e = Endpoints(login = "/x/in", refresh = "/x/renew", logout = "/x/out", devices = "/x/dev")
        assertEquals("/x/renew", PlainJsonContract(e).refreshPath); assertEquals("/x/out", EnvelopeContract(e).logoutPath)
    }

    @Test fun authPathsAreMatchedExactlyNotGuessedFromTheName() {
        val c = PlainJsonContract(Endpoints.taper())
        assertTrue(c.isAuthPath("/api/v2/auth/login")); assertTrue(c.isAuthPath("/api/v2/auth/refresh")); assertFalse(c.isAuthPath("/api/v2/auth/logout?all=1"), "đăng xuất cần Bearer (server bắt xác thực để thu hồi phiên)")
        assertFalse(c.isAuthPath("/api/v2/activities/login-history"), "đường thường có chữ login không bị coi là đường đăng nhập")
        assertFalse(c.isAuthPath("/api/v2/me/refresh-rate"))
    }

    @Test fun missingEndpointsFailLoudly() {
        val e = Endpoints(login = "/l", refresh = "/r", logout = "/o")
        assertFailsWith<IllegalStateException> { e["nope"] }
        assertFailsWith<IllegalStateException> { e.requireDevices() }
        assertEquals("/api/v2/activities", Endpoints.taper(extra = mapOf("activities" to "/api/v2/activities"))["activities"])
    }
}
