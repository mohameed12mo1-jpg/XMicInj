package com.xmicinject

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// Android-side TCP server transport.
//
// The original XMicInject transport made the injected app connect OUT to
// 127.0.0.1:38673 and relied on `adb reverse`. On some devices/app UIDs that
// loopback listener is not reachable from the app process even though shell/root
// can reach it. This variant flips the direction:
//
//   PC provider -> adb forward tcp:38673 tcp:38673 -> this ServerSocket
//
// The socket remains full duplex:
//   input  : translated PCM from provider -> PcmRingBuffer (inject)
//   output : real mic uplink from hooks    -> provider
internal object IpcClient {

    private const val TAG = "XMicIpcClient"
    private const val HOST = "127.0.0.1"
    private const val PORT = 38673
    private const val READ_CHUNK = 4096

    @Volatile private var started = false
    @Volatile private var output: OutputStream? = null

    // True while a PC provider is connected. The hook uses this to mute the
    // real microphone when the injected PCM buffer temporarily runs empty.
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

    fun write(data: ByteArray) {
        output?.let { stream ->
            runCatching {
                stream.write(data)
            }.onFailure {
                output = null
            }
        }
    }

    private fun serverLoop() {
        while (true) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, PORT))
                Log.i(TAG, "Listening on $HOST:$PORT (adb forward mode)")

                while (true) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Server failed: ${t.message}")
            } finally {
                muteRealMic = false
                output = null
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
            Log.i(TAG, "Provider connected from ${socket.inetAddress.hostAddress}:${socket.port}")
            output = socket.outputStream
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
            output = null
            PcmRingBuffer.clear()
            UplinkSender.reset()
            runCatching { socket.close() }
            Log.i(TAG, "Provider disconnected")
        }
    }
}
