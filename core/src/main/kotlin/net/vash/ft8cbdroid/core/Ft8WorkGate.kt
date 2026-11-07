package net.vash.ft8cbdroid.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

class Ft8EngineBusyException : IllegalStateException("The offline engine is busy. Wait or cancel the current request, then retry.")
class Ft8EngineTimeoutException(message: String) : IllegalStateException(message)

/** One running call and one FIFO waiter; admission happens before dispatcher work or JNI copies. */
class Ft8WorkGate {
    private val capacity = Semaphore(2)
    private val serial = Mutex()
    val admittedCount: Int get() = 2 - capacity.availablePermits

    suspend fun <T> run(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!capacity.tryAcquire()) throw Ft8EngineBusyException()
        try {
            return serial.withLock {
                currentCoroutineContext().ensureActive()
                block()
            }
        } finally {
            capacity.release()
        }
    }
}
