package com.happytalk.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface

class UdpAudioListener {
    private var isListening = false
    private var job: Job? = null
    private var audioTrack: AudioTrack? = null
    private var socket: DatagramSocket? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val port = 50505

    fun startListening() {
        if (isListening) return
        isListening = true
        Log.i("UdpAudioListener", "Starting UDP listener on port $port...")

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize * 10)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                socket = DatagramSocket(null)
                socket?.reuseAddress = true
                socket?.bind(InetSocketAddress(port))

                val buffer = ByteArray(minBufSize * 2)

                val myIps = mutableListOf<String>()
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val networkInterface = interfaces.nextElement()
                        for (address in networkInterface.inetAddresses) {
                            if (!address.isLoopbackAddress) {
                                myIps.add(address.hostAddress ?: "")
                            }
                        }
                    }
                } catch (e: Exception) {}

                while (isActive && isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    if (myIps.contains(packet.address.hostAddress)) continue

                    // If we receive data from someone else, play it instantly
                    if (packet.length > 0) {
                        audioTrack?.write(packet.data, 0, packet.length)
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpAudioListener", "Listen failed", e)
            } finally {
                cleanup()
            }
        }
    }

    fun stopListening() {
        isListening = false
        job?.cancel()
        cleanup()
    }

    private fun cleanup() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        
        audioTrack = null
        socket = null
        Log.i("UdpAudioListener", "UDP listener stopped.")
    }
}
