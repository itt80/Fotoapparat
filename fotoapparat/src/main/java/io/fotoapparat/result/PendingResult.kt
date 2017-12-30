package io.fotoapparat.result

import io.fotoapparat.capability.Capabilities
import io.fotoapparat.exception.RecoverableRuntimeException
import io.fotoapparat.exception.UnableToDecodeBitmapException
import io.fotoapparat.hardware.executeMainThread
import io.fotoapparat.hardware.pendingResultExecutor
import io.fotoapparat.log.Logger
import io.fotoapparat.parameter.camera.CameraParameters
import java.util.concurrent.*


/**
 * Result which might not be readily available at the given moment but will be available in the
 * future.
 */
open class PendingResult<T>
internal constructor(
        private val future: Future<T>,
        private val logger: Logger,
        private val executor: Executor
) {
    private val resultUnsafe: T
        get() {
            return try {
                future.get()
            } catch (e: InterruptedException) {
                throw RecoverableRuntimeException(e)
            } catch (e: ExecutionException) {
                throw RecoverableRuntimeException(e)
            }
        }

    /**
     * Transforms result from one type to another.
     *
     * @param transformer function which performs transformation of current result type to a new
     * type.
     * @return [PendingResult] of another type.
     */
    fun <R> transform(transformer: (T) -> R): PendingResult<R> {
        val transformTask = FutureTask(Callable<R> {
            transformer(future.get())
        })

        executor.execute(transformTask)

        return PendingResult<R>(
                future = transformTask,
                logger = logger,
                executor = executor
        )
    }

    /**
     * Blocks current thread until result is available.
     *
     * @return result of execution.
     * @throws ExecutionException if the result couldn't be obtained.
     * @throws InterruptedException if the thread has been interrupted.
     */
    @Throws(ExecutionException::class, InterruptedException::class)
    fun await(): T {
        return future.get()
    }

    /**
     * Adapts the resulting object to a different type.
     *
     * @param adapter Function which performs transforms the current result callback to a new
     * type.
     * @return result adapted to a new type.
     */
    fun <R> adapt(adapter: (Future<T>) -> R): R {
        return adapter(future)
    }

    /**
     * Notifies given callback as soon as result is available. Callback will always be notified on
     * a main thread.
     *
     * If the result couldn't be obtained, a null value will be returned.
     */
    fun whenAvailable(callback: (T?) -> Unit) {
        executor.execute {
            try {
                resultUnsafe.notifyCallbackOnMainThread(callback)
            } catch (e: UnableToDecodeBitmapException) {
                logger.log("Couldn't decode bitmap from byte array")
            } catch (e: RecoverableRuntimeException) {
                logger.log("Couldn't deliver pending result.")
                callback(null)
            }
        }
    }

    /**
     * Alias for [PendingResult.whenAvailable] for java.
     */
    fun whenDone(callback: WhenDoneListener<T>) {
        whenAvailable { callback.whenDone(it) }
    }

    companion object {
        /**
         * @return [PendingResult] which waits for the result of [Future].
         */
        internal fun <T> fromFuture(
                future: Future<T>,
                logger: Logger
        ): PendingResult<T> = PendingResult(
                future = future,
                logger = logger,
                executor = pendingResultExecutor
        )
    }

}

private fun <T> T.notifyCallbackOnMainThread(callback: (T) -> Unit) {
    executeMainThread {
        callback(this)
    }
}

interface WhenDoneListener<T> {
    fun whenDone(it: T?)
}

typealias CapabilitiesResult = PendingResult<Capabilities>
typealias ParametersResult = PendingResult<CameraParameters>
