package com.m3uplayer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath
import android.net.Uri
import com.m3uplayer.app.data.AppSettings
import com.m3uplayer.app.data.Net
import com.m3uplayer.app.data.PlaylistLoader
import com.m3uplayer.app.data.TrustAwareCallFactory
import com.m3uplayer.app.data.WatchHistory
import com.m3uplayer.app.download.DownloadManager
import com.m3uplayer.app.download.DownloadService
import com.m3uplayer.app.download.RoutingSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Objetos compartilhados pelo app inteiro (lista, histórico, downloads). */
class M3UApp : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", Net.USER_AGENT).build()
                    else request
                )
            }
            .build()
    }

    val settings by lazy { AppSettings(this) }

    /** Use para tudo que acessa servidores da lista: respeita os servidores marcados como confiáveis. */
    val callFactory: Call.Factory by lazy { TrustAwareCallFactory(http) { host -> host in settings.trustedHosts } }

    val history by lazy { WatchHistory(File(filesDir, "history.json")) }
    val loader by lazy {
        PlaylistLoader(File(filesDir, "lists"), callFactory) { source ->
            contentResolver.openInputStream(Uri.parse(source)) ?: throw IOException("Não foi possível abrir o arquivo")
        }
    }

    /**
     * Imagens: cache em memória e em disco (300 MB), tempo de espera generoso para servidores lentos
     * e até 8 downloads em paralelo por servidor.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val imageHttp = http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 8 })
            .build()
        val imageCalls = TrustAwareCallFactory(imageHttp) { host -> host in settings.trustedHosts }
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageCalls })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(cacheDir, "images").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(150)
            .build()
    }

    val downloads by lazy {
        DownloadManager(callFactory, RoutingSink(this, { settings.moviesFolder }, { settings.seriesFolder }), appScope).also { manager ->
            manager.onActiveChanged = { active -> if (active) DownloadService.start(this) }
        }
    }
}
