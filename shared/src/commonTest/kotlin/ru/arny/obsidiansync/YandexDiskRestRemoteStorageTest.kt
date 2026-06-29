package ru.arny.obsidiansync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import ru.arny.obsidiansync.core.RemotePath
import ru.arny.obsidiansync.remote.yandex.YandexDiskRestRemoteStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class YandexDiskRestRemoteStorageTest {
    @Test
    fun authorizationUsesYandexOAuthScheme() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"total_space":100,"used_space":40}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val storage = YandexDiskRestRemoteStorage(
            httpClient = HttpClient(engine),
            oauthToken = "test-token",
            root = RemotePath("disk:/ObsidianSyncTest"),
        )

        storage.getQuota()

        assertEquals("OAuth test-token", authorization)
    }
}
