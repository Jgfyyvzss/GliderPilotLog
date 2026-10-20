package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The WeGlide client against a local mock server, and the aircraft catalog against a fake. Test code only: none of
 * this is in the app, and no test calls the real WeGlide API.
 */
class WeGlideClientTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("weglide").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun api() = OkHttpWeGlideApi(OkHttpWeGlideApi.userAgent("1.2.3"), baseUrl = server.url("/").toString())

    @Test fun requestsTheAircraftListWithTheUserAgent() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1,"name":"ASK 21"}]"""))
        val r = api().fetchAircraftJson()
        assertEquals(ApiResult.Ok("""[{"id":1,"name":"ASK 21"}]"""), r)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/aircraft", request.path)
        assertEquals("Mozilla/5.0 (Linux; Android) soarlogs/1.2.3", request.getHeader("User-Agent"))
    }

    @Test fun anErrorStatusIsAFailureWithTheStartOfTheMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable " + "x".repeat(500)))
        val r = api().fetchAircraftJson() as ApiResult.Failed
        assertTrue(r.message, r.message.startsWith("WeGlide answered 503: Service unavailable"))
        assertTrue(r.message.length < 340)
    }

    @Test fun anUnreachableServerIsAFailure() = runTest {
        server.shutdown()
        assertTrue(api().fetchAircraftJson() is ApiResult.Failed)
    }

    /** A stand-in for WeGlide that returns a fixed answer. */
    private class FakeApi(var answer: ApiResult<String>) : WeGlideApi {
        override suspend fun fetchAircraftJson() = answer
    }

    @Test fun catalogUsesTheBundledListUntilUpdated() = runTest {
        val saved = File(dir, "aircraft.json")
        val fake = FakeApi(ApiResult.Ok("""[{"id":1,"name":"ASK 21"},{"id":2,"name":"Discus 2"}]"""))
        val catalog = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake, now = { 1000L })

        val first = catalog.load()
        assertEquals(listOf("Bundled"), first.aircraft.map { it.name })
        assertNull(first.updatedAt)

        assertEquals(Result.success(2), catalog.update())
        assertEquals(listOf("ASK 21", "Discus 2"), catalog.list.value!!.aircraft.map { it.name })
        assertEquals(1000L, catalog.list.value!!.updatedAt)
        assertTrue(saved.exists())

        // A new catalog, as after restarting the app, reads the saved copy.
        val restarted = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake)
        assertEquals("Discus 2", restarted.find(2)?.name)
    }

    @Test fun failedOrUnusableUpdatesKeepTheListInUse() = runTest {
        val saved = File(dir, "aircraft.json")
        val fake = FakeApi(ApiResult.Failed("Could not reach WeGlide: timeout"))
        val catalog = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake)
        catalog.load()

        assertEquals("Could not reach WeGlide: timeout", catalog.update().exceptionOrNull()?.message)
        fake.answer = ApiResult.Ok("<html>maintenance</html>")
        assertEquals("WeGlide sent no usable aircraft list", catalog.update().exceptionOrNull()?.message)
        fake.answer = ApiResult.Ok("[]")
        assertTrue(catalog.update().isFailure)

        assertEquals(listOf("Bundled"), catalog.list.value!!.aircraft.map { it.name })
        assertTrue(!saved.exists())
    }
}
