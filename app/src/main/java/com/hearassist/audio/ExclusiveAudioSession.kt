package com.hearassist.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 下一个录音会话必须等上一个会话的子任务和原生调用退出、资源释放完成。 */
class ExclusiveAudioSession {
    private val mutex = Mutex()

    suspend fun <R : AutoCloseable, T> run(
        resources: () -> R,
        block: suspend CoroutineScope.(R) -> T,
    ): T = mutex.withLock {
        coroutineContext.ensureActive()
        val owned = resources()
        try {
            coroutineScope { block(owned) }
        } finally {
            withContext(NonCancellable) { owned.close() }
        }
    }
}

object AudioSessions {
    val gate = ExclusiveAudioSession()
}
