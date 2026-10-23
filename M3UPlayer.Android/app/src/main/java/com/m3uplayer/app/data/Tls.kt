package com.m3uplayer.app.data

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/**
 * Muitos servidores IPTV usam certificados HTTPS que o Android não reconhece (autoassinados, de autoridades
 * desconhecidas ou com a cadeia incompleta). O usuário pode marcar um servidor como confiável: só para ele
 * a verificação do certificado é desligada.
 */
object Tls {
    /** Cliente igual ao [base], mas que aceita qualquer certificado. Use só para servidores confiáveis. */
    fun lenient(base: OkHttpClient): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** O erro foi causado por um certificado HTTPS não reconhecido? */
    fun isCertificateProblem(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(12).any {
            it is SSLHandshakeException || it is SSLPeerUnverifiedException ||
                it is CertificateException || it is CertPathValidatorException
        }

    fun hostOf(url: String): String? = runCatching { URI(url.trim().replace(" ", "%20")).host }.getOrNull()?.lowercase()
}

/** Escolhe, a cada requisição, o cliente normal ou o "confiável" conforme o servidor. */
class TrustAwareCallFactory(
    private val strict: OkHttpClient,
    private val isTrusted: (String) -> Boolean,
) : Call.Factory {
    private val lenient by lazy { Tls.lenient(strict) }

    override fun newCall(request: Request): Call =
        (if (isTrusted(request.url.host.lowercase())) lenient else strict).newCall(request)
}
