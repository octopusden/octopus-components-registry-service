package org.octopusden.octopus.components.registry.cli.auth

import org.octopusden.octopus.components.registry.cli.client.HttpExchange
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession

/** Minimal in-memory HttpResponse<String> for the auth-layer fakes. */
internal class FakeResponse(
    private val status: Int,
    private val body: String?,
    private val req: HttpRequest,
) : HttpResponse<String> {
    override fun statusCode(): Int = status
    override fun request(): HttpRequest = req
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): String? = body
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): java.net.URI = req.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

/** Replays the next queued (status, body) per call and records every request. */
internal class QueueExchange(
    private val replies: List<Pair<Int, String?>>,
) : HttpExchange {
    val requests = mutableListOf<HttpRequest>()
    override fun send(request: HttpRequest): HttpResponse<String> {
        requests += request
        val (status, body) = replies[requests.size - 1]
        return FakeResponse(status, body, request)
    }

    fun bodyOf(index: Int): String =
        requests[index].bodyPublisher()
            .map { publisher ->
                val sub = StringBodySubscriber()
                publisher.subscribe(sub)
                sub.text
            }
            .orElse("")
}

/** Records every sleep so tests can assert the polling intervals without real waiting. */
internal class RecordingSleeper : Sleeper {
    val slept = mutableListOf<Long>()
    override fun sleep(millis: Long) {
        slept += millis
    }
}

/** Records calls to a [CredentialStore] and holds an in-memory value. */
internal class RecordingStore(initial: String? = null) : CredentialStore {
    var value: String? = initial
    val calls = mutableListOf<String>()
    override fun load(): String? {
        calls += "load"
        return value
    }

    override fun save(refreshToken: String) {
        calls += "save"
        value = refreshToken
    }

    override fun clear() {
        calls += "clear"
        value = null
    }
}

/** Records each `security` invocation and replies from a queue of canned [CommandResult]s. */
internal class FakeCommandRunner(
    private val results: List<CommandResult>,
) : CommandRunner {
    val invocations = mutableListOf<List<String>>()
    override fun run(args: List<String>, stdin: String?): CommandResult {
        invocations += args
        return results[invocations.size - 1]
    }
}

/** Drains an HTTP body publisher into a String so request form bodies can be asserted. */
private class StringBodySubscriber : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
    private val sb = StringBuilder()
    val text: String get() = sb.toString()
    override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: java.nio.ByteBuffer) {
        val bytes = ByteArray(item.remaining())
        item.get(bytes)
        sb.append(String(bytes, Charsets.UTF_8))
    }

    override fun onError(throwable: Throwable) = Unit
    override fun onComplete() = Unit
}
