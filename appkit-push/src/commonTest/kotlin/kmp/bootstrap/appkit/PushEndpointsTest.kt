package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.push.DeviceInfo
import kmp.bootstrap.appkit.push.DevicesPushContract
import kmp.bootstrap.appkit.push.PushRegistration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PushEndpointsTest {
    @Test fun pushContractsTakeTheirPathFromEndpoints() {
        val e = Endpoints(login = "/x/in", refresh = "/x/renew", logout = "/x/out", devices = "/x/dev")
        val call = DevicesPushContract(e).register(PushRegistration("d", "t", DeviceInfo("n", "m", "os", "android"), "1"))
        assertEquals("/x/dev", call.path)
        assertEquals("/x/dev/d", DevicesPushContract(e).unregister("d").path)
    }

    @Test fun pushWithoutADevicesEndpointFailsLoudly() {
        assertFailsWith<IllegalStateException> { DevicesPushContract(Endpoints(login = "/l", refresh = "/r", logout = "/o")).unregister("x") }
    }
}
