package dev.benfranklin.tether.log

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor

/**
 * A single-threaded scheduled executor that logs what its tasks throw. A plain
 * ScheduledThreadPoolExecutor keeps a task's exception inside its Future, so a failing task
 * vanished without a trace (0.3.0's wedged Bluetooth writes did exactly that). Each failure
 * becomes an `executor.uncaught` line in Export diagnostics; the thread keeps running.
 */
class LoggedExecutor(private val name: String) :
    ScheduledThreadPoolExecutor(1, { r -> Thread(r, name).apply { isDaemon = true } }) {

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        super.afterExecute(r, t)
        val error = t ?: (r as? Future<*>)?.let(::failureOf)
        if (error != null) Log.e("executor.uncaught", error, "thread" to name)
    }

    private fun failureOf(f: Future<*>): Throwable? {
        if (!f.isDone || f.isCancelled) return null
        return try {
            f.get()
            null
        } catch (e: ExecutionException) {
            e.cause ?: e
        } catch (_: CancellationException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
