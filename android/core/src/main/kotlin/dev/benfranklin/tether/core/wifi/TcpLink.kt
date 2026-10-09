package dev.benfranklin.tether.core.wifi

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One TCP connection carrying length-prefixed records. A reader thread and a writer thread;
 * callbacks are delivered on [callbacks] (the caller's serial executor) so the controller
 * stays single-threaded.
 */
class TcpLink(
    private val socket: Socket,
    private val callbacks: Executor,
    private val onRecord: (ByteArray) -> Unit,
    private val onClosed: (IOException?) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    /** Bytes waiting to be written; used for backpressure-aware sending. */
    private val pending = AtomicLong(0)
    val queuedBytes: Long get() = pending.get()

    fun start() {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        Thread({ readLoop() }, "tether-tcp-read").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "tether-tcp-write").apply { isDaemon = true }.start()
    }

    fun write(record: ByteArray) {
        if (closed.get()) return
        pending.addAndGet(record.size.toLong())
        queue.offer(record)
    }

    fun close() = shutdown(null)

    private fun readLoop() {
        try {
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            while (!closed.get()) {
                val record = StreamFraming.read(input) ?: break
                callbacks.execute { if (!closed.get()) onRecord(record) }
            }
            shutdown(null)
        } catch (e: IOException) {
            shutdown(e)
        }
    }

    private fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            while (!closed.get()) {
                val r = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                StreamFraming.write(out, r)
                pending.addAndGet(-r.size.toLong())
                if (queue.isEmpty()) out.flush()
            }
        } catch (e: IOException) {
            shutdown(e)
        }
    }

    private fun shutdown(error: IOException?) {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: IOException) {
            // already closed
        }
        queue.clear()
        callbacks.execute { onClosed(error) }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val POLL_MS = 250L
    }
}
