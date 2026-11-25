package dev.lelonio.square.common

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Shared OkHttp client for metadata/lyrics fetchers so providers reuse one bounded dispatcher
 * and connection pool instead of spawning per-provider TaskRunner and socket threads.
 */
object SharedHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 4 })
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun clientWithCallTimeout(seconds: Long): OkHttpClient =
        client.newBuilder().callTimeout(seconds, TimeUnit.SECONDS).build()

    fun evictAll() {
        runCatching { client.connectionPool.evictAll() }
    }
}

/**
 * Runs [block], propagating coroutine cancellation rather than swallowing it inside Result.
 */
inline fun <T> runCancellableCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * Executes an OkHttp request while cancelling the underlying call immediately if the
 * calling coroutine is cancelled, preventing orphaned blocking calls and thread growth.
 */
suspend inline fun <T> OkHttpClient.executeCancellable(request: Request, crossinline block: (Response) -> T): T {
    val ctx = currentCoroutineContext()
    ctx.ensureActive()
    val call = newCall(request)
    val handle = ctx.job.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    return try {
        call.execute().use(block)
    } catch (e: Throwable) {
        if (!ctx.job.isActive) {
            throw (e as? CancellationException) ?: CancellationException("HTTP call cancelled", e)
        }
        throw e
    } finally {
        handle.dispose()
    }
}
