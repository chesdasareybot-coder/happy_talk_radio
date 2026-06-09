package com.happytalk.radio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpAudioBroadcaster {
    private var isBroadcasting = false
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var socket: DatagramSocket? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val port = 50505

    @SuppressLint("MissingPermission")
    fun startBroadcasting(broadcastAddress: InetAddress) {
        if (isBroadcasting) return
        isBroadcasting = true
        Log.i("UdpAudioBroadcaster", "Starting UDP broadcast to ${broadcastAddress.hostAddress}...")

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufSize * 10
                )
                
                socket = DatagramSocket()
                socket?.broadcast = true

                val buffer = ByteArray(minBufSize)
                audioRecord?.startRecording()

                while (isActive && isBroadcasting) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val packet = DatagramPacket(buffer, readSize, broadcastAddress, port)
                        socket?.send(packet)
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpAudioBroadcaster", "Broadcast failed", e)
            } finally {
                cleanup()
            }
        }
    }

    fun stopBroadcasting() {
        isBroadcasting = false
        job?.cancel()
        cleanup()
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        
        audioRecord = null
        socket = null
        Log.i("UdpAudioBroadcaster", "UDP broadcast stopped.")
    }
}
