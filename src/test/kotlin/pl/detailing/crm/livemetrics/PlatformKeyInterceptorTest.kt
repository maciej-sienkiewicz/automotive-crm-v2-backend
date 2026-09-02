package pl.detailing.crm.livemetrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.platform.PlatformKeyInterceptor

class PlatformKeyInterceptorTest {

    private fun request(key: String?) = MockHttpServletRequest("GET", "/api/internal/live-metrics/overview").apply {
        key?.let { addHeader(PlatformKeyInterceptor.HEADER, it) }
    }

    @Test
    fun `no configured key fails closed with 503`() {
        val interceptor = PlatformKeyInterceptor(LiveMetricsProperties(platformApiKey = ""))
        val response = MockHttpServletResponse()
        assertFalse(interceptor.preHandle(request(""), response, Any()))
        assertEquals(503, response.status)
    }

    @Test
    fun `wrong or missing key is rejected with 401`() {
        val interceptor = PlatformKeyInterceptor(LiveMetricsProperties(platformApiKey = "secret"))
        val r1 = MockHttpServletResponse()
        assertFalse(interceptor.preHandle(request("nope"), r1, Any()))
        assertEquals(401, r1.status)
        val r2 = MockHttpServletResponse()
        assertFalse(interceptor.preHandle(request(null), r2, Any()))
        assertEquals(401, r2.status)
    }

    @Test
    fun `matching key passes`() {
        val interceptor = PlatformKeyInterceptor(LiveMetricsProperties(platformApiKey = "secret"))
        assertTrue(interceptor.preHandle(request("secret"), MockHttpServletResponse(), Any()))
    }
}
