package io.github.jgfyyvzss.soarlog.data.weglide

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.english
import java.io.File
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideCredentials
import java.nio.file.Files
import java.time.LocalDate
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
        assertEquals("Mozilla/5.0 (Linux; Android) SoarLog/1.2.3", request.getHeader("User-Agent"))
    }

    @Test fun anErrorStatusIsAFailureWithTheStartOfTheMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable " + "x".repeat(500)))
        val r = (api().fetchAircraftJson() as ApiResult.Failed).message.english()
        assertTrue(r, r.startsWith("WeGlide answered 503: Service unavailable"))
        assertTrue(r.length < 340)
    }

    @Test fun anUnreachableServerIsAFailure() = runTest {
        server.shutdown()
        assertTrue(api().fetchAircraftJson() is ApiResult.Failed)
    }

    // Upload. The ID and date of birth are made-up test values, not anyone's account.
    private val credentials = WeGlideCredentials("12345", LocalDate.of(1970, 1, 2))

    private fun fixture(name: String): ByteArray =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readBytes()

    @Test fun uploadSendsTheFileAndFieldsAsMultipartForm() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        val igc = fixture("52DVPSA1.igc")
        assertTrue(api().uploadIgc(igc, "52DVPSA1.igc", credentials, 1234) is ApiResult.Ok)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/igcfile", request.path)
        assertEquals("Mozilla/5.0 (Linux; Android) SoarLog/1.2.3", request.getHeader("User-Agent"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("""name="file"; filename="52DVPSA1.igc""""))
        assertTrue("the file goes byte for byte", body.contains(igc.toString(Charsets.ISO_8859_1)))
        listOf("user_id" to "12345", "date_of_birth" to "1970-01-02", "aircraft_id" to "1234").forEach { (name, value) ->
            assertTrue(name, Regex("""name=""\r\n(?:[^\r\n]+\r\n)*\r\n\r\n""").containsMatchIn(body))
        }
    }

    @Test fun uploadNamesAFileWithoutTheIgcExtension() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        api().uploadIgc(fixture("52DVPSA1.igc"), "download", credentials, 1)
        assertTrue(server.takeRequest().body.readUtf8().contains("""filename="download.igc""""))
    }

    @Test fun uploadOnlySucceedsOn201() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(api().uploadIgc(fixture("52DVPSA1.igc"), "a.igc", credentials, 1) is ApiResult.Failed)
        server.enqueue(MockResponse().setResponseCode(400).setBody("Invalid date of birth " + "y".repeat(500)))
        val r = (api().uploadIgc(fixture("52DVPSA1.igc"), "a.igc", credentials, 1) as ApiResult.Failed).message.english()
        assertTrue(r, r.startsWith("WeGlide answered 400: Invalid date of birth"))
        assertTrue(r.length < 340)
    }

    @Test fun uploadToAnUnreachableServerIsAFailure() = runTest {
        server.shutdown()
        assertTrue(api().uploadIgc(fixture("52DVPSA1.igc"), "a.igc", credentials, 1) is ApiResult.Failed)
    }

    @Test fun credentialsNeverShowInText() {
        assertEquals("WeGlideCredentials(hidden)", credentials.toString())
        assertTrue(WeGlideCredentials.validUserId("12345"))
        assertTrue(!WeGlideCredentials.validUserId("12a45"))
        assertTrue(!WeGlideCredentials.validUserId(""))
    }

    /** A stand-in for WeGlide that returns a fixed answer. */
    private class FakeApi(var answer: ApiResult<String>) : WeGlideApi {
        override suspend fun fetchAircraftJson() = answer
        override suspend fun uploadIgc(file: ByteArray, fileName: String, credentials: WeGlideCredentials, aircraftId: Long) = answer
    }

    @Test fun catalogUsesTheBundledListUntilUpdated() = runTest {
        val saved = File(dir, "aircraft.json")
        val fake = FakeApi(ApiResult.Ok("""[{"id":1,"name":"ASK 21"},{"id":2,"name":"Discus 2"}]"""))
        val catalog = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake, now = { 1000L })

        val first = catalog.load()
        assertEquals(listOf("Bundled"), first.aircraft.map { it.name })
        assertNull(first.updatedAt)

        assertEquals(AircraftUpdate.Updated(2), catalog.update())
        assertEquals(listOf("ASK 21", "Discus 2"), catalog.list.value!!.aircraft.map { it.name })
        assertEquals(1000L, catalog.list.value!!.updatedAt)
        assertTrue(saved.exists())

        // A new catalog, as after restarting the app, reads the saved copy.
        val restarted = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake)
        assertEquals("Discus 2", restarted.find(2)?.name)
    }

    @Test fun failedOrUnusableUpdatesKeepTheListInUse() = runTest {
        val saved = File(dir, "aircraft.json")
        val fake = FakeApi(ApiResult.Failed(msg(MsgKey.WEGLIDE_UNREACHABLE, "timeout")))
        val catalog = AircraftCatalog(saved, bundled = { """[{"id":9,"name":"Bundled"}]""" }, api = fake)
        catalog.load()

        assertEquals("Could not reach WeGlide: timeout", (catalog.update() as AircraftUpdate.Failed).message.english())
        fake.answer = ApiResult.Ok("<html>maintenance</html>")
        assertEquals(AircraftUpdate.Failed(msg(MsgKey.WEGLIDE_NO_LIST)), catalog.update())
        fake.answer = ApiResult.Ok("[]")
        assertTrue(catalog.update() is AircraftUpdate.Failed)

        assertEquals(listOf("Bundled"), catalog.list.value!!.aircraft.map { it.name })
        assertTrue(!saved.exists())
    }
}
