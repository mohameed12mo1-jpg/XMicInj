package com.xmicinject

import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// Android-side TCP server transport.
//
// Direct LAN / USB-tether network mode for Wevo:
//   PC provider -> phone IP:38673 -> PcmRingBuffer -> AudioRecord injection
//
// No adb forward/reverse is used for the audio stream, so ADB remains free for
// scrcpy, shell commands and logcat. Uplink from the phone mic is disabled.
internal object IpcClient {

    private const val TAG = "XMicIpcClient"
    private const val HOST = "0.0.0.0"
    private const val PORT = 38673
    private const val READ_CHUNK = 4096

    @Volatile private var started = false

    @Volatile var muteRealMic: Boolean = false
        private set

    fun startOnce() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val thread = Thread(::serverLoop)
            thread.isDaemon = true
            thread.name = "xmicinject-server"
            thread.start()
        }
    }

    // Uplink intentionally disabled in one-way mode.
    fun write(data: ByteArray) {
        // no-op
    }

    private fun serverLoop() {
        while (true) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, PORT))
                Log.i(TAG, "Listening on $HOST:$PORT (direct one-way mode, no adb tunnel)")

                while (true) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Server failed: ${t.message}")
            } finally {
                muteRealMic = false
                PcmRingBuffer.clear()
                UplinkSender.reset()
                runCatching { server?.close() }
            }
            Thread.sleep(1000)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 64 * 1024
            Log.i(TAG, "Provider connected from ${socket.inetAddress.hostAddress}:${socket.port} (direct one-way)")
            muteRealMic = true

            val input = socket.inputStream
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                PcmRingBuffer.write(buf, 0, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Provider connection ended: ${t.message}")
        } finally {
            muteRealMic = false
            PcmRingBuffer.clear()
            UplinkSender.reset()
            runCatching { socket.close() }
            Log.i(TAG, "Provider disconnected")
        }
    }
}
