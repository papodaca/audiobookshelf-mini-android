package io.github.miniaudiobookshelf.android.http

import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.HttpResponse
import io.github.miniaudiobookshelf.core.HttpTransport
import io.github.miniaudiobookshelf.core.SettingsStore
import io.github.miniaudiobookshelf.core.TlsHandshakeException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.tls.HandshakeCertificates

class OkHttpTransport(
    private val settings: SettingsStore,
) : HttpTransport {
    private val recorder = RecordingTrustManager(platformTrustManager())
    @Volatile
    var lastPeerPem: String = ""
        private set

    @Volatile
    private var client: OkHttpClient = buildClient()

    fun rebuild() {
        recorder.reset(trustManagerWithPins(settings.getPinnedCertificates().values))
        lastPeerPem = ""
        client = buildClient()
    }

    override suspend fun send(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val requestBody = when {
            body != null -> body.toRequestBody(JSON)
            method == "GET" || method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val builder = Request.Builder().url(uri).method(method, requestBody)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    HttpResponse(
                        status = resp.code,
                        body = resp.body?.string().orEmpty(),
                        location = resp.header("Location"),
                    )
                }
            }
        } catch (e: SSLHandshakeException) {
            lastPeerPem = recorder.lastPem()
            throw TlsHandshakeException("tls validation failed", e)
        } catch (e: SSLPeerUnverifiedException) {
            lastPeerPem = recorder.lastPem()
            throw TlsHandshakeException("tls validation failed", e)
        }
    }

    private fun buildClient(): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(recorder), null)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, recorder)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(CleartextInterceptor(settings))
            .addInterceptor(RedactingLogInterceptor())
            .build()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private class CleartextInterceptor(
    private val settings: SettingsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val url = chain.request().url
        if (url.scheme == "http") {
            val origin = httpOrigin(url.scheme, url.host, url.port)
            if (!settings.isHttpAcknowledged(origin)) {
                throw java.io.IOException("cleartext not acknowledged")
            }
        }
        return chain.proceed(chain.request())
    }
}

private class RedactingLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val headers = request.headers.names().associateWith { request.header(it).orEmpty() }
        android.util.Log.d("MiniAbsHttp", ApiClient.formatLogLine(request.method, request.url.toString(), headers))
        return chain.proceed(request)
    }
}

internal fun httpOrigin(scheme: String, host: String, port: Int): String {
    val lowerHost = host.lowercase(Locale.ROOT)
    val elide = (scheme == "https" && (port == 443 || port == -1)) ||
        (scheme == "http" && (port == 80 || port == -1))
    return if (elide) "$scheme://$lowerHost" else "$scheme://$lowerHost:$port"
}

internal fun certificateToPem(cert: X509Certificate): String {
    val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(cert.encoded)
    return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
}

internal fun parsePem(pem: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    return factory.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
}

private fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private fun trustManagerWithPins(pems: Collection<String>): X509TrustManager {
    val builder = HandshakeCertificates.Builder().addPlatformTrustedCertificates()
    for (pem in pems) {
        try {
            builder.addTrustedCertificate(parsePem(pem))
        } catch (_: Exception) {
        }
    }
    return builder.build().trustManager
}

private class RecordingTrustManager(
    @Volatile private var delegate: X509TrustManager,
) : X509TrustManager {
    @Volatile
    private var lastChain: Array<X509Certificate>? = null

    fun reset(delegate: X509TrustManager) {
        this.delegate = delegate
        lastChain = null
    }

    fun lastPem(): String {
        val cert = lastChain?.firstOrNull() ?: return ""
        return certificateToPem(cert)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        lastChain = chain
        delegate.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
