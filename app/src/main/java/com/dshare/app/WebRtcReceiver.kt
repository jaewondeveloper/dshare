package com.dshare.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

class WebRtcReceiver(
    context: Context,
    private val renderer: SurfaceViewRenderer,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onLocalIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onAnswerCreated(sdp: String)
        fun onRemoteConnected()
        fun onConnectionClosed()
    }

    companion object {
        // PeerConnectionState.DISCONNECTED is often transient - a brief Wi-Fi hiccup,
        // roaming between APs, a missed STUN keepalive - and ICE frequently recovers to
        // CONNECTED again on its own within a second or two without any action needed.
        // Treating it as fatal immediately (like the previous code did, lumping it in
        // with FAILED/CLOSED) tore the whole session down on every brief blip, which is
        // exactly what "shares fine for a bit, then keeps disconnecting" looks like on
        // an ordinary home Wi-Fi network. Give it this long to recover before giving up.
        private const val DISCONNECT_GRACE_MS = 6000L
    }

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val mainHandler = Handler(Looper.getMainLooper())
    private var peerConnection: PeerConnection? = null
    private var attachedTrack: VideoTrack? = null
    private var disconnectTimeoutRunnable: Runnable? = null

    // onTrack and onAddStream can both fire for the same track (Unified Plan still
    // raises the legacy onAddStream for compatibility), and renegotiation can raise
    // either again for a track that's already attached. Without dedup, the renderer
    // was accumulating multiple sink registrations on the same track - meaning it
    // decoded/rendered every frame N times over - which compounds over a session and
    // matches the reported "fine at first, then progressively laggier" symptom.
    private fun attachVideoTrack(track: VideoTrack) {
        if (attachedTrack === track) return
        attachedTrack?.removeSink(renderer)
        track.addSink(renderer)
        attachedTrack = track
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // Without this, WebRTC's default audio setup routes playback as a voice call
        // (USAGE_VOICE_COMMUNICATION), which Android maps to the phone call volume
        // stream - hence showing as "call" and having a much louder, coarser volume
        // floor than normal media. This is a screen-share viewer, not a call: route
        // playback through the regular media stream instead.
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
    }

    fun handleOffer(sdp: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        // Local to this connection attempt: SurfaceViewRenderer's own
        // onFirstFrameRendered() fires only once for the renderer's whole lifetime
        // (WebRTC never resets that internal flag), so it can't be used to detect
        // "connected" on a second connection - a reconnect or a different device
        // joining after the first session ends would hang forever waiting for it.
        // The PeerConnection's own state is fresh every time, so use that instead.
        var connectedNotified = false

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                android.util.Log.i("DShare", "local ICE candidate: ${candidate.sdp}")
                callbacks.onLocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                android.util.Log.i("DShare", "onTrack fired, kind=${transceiver.receiver.track()?.kind()}")
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    attachVideoTrack(track)
                }
            }

            override fun onAddStream(stream: MediaStream) {
                android.util.Log.i("DShare", "onAddStream fired, videoTracks=${stream.videoTracks.size}")
                stream.videoTracks.firstOrNull()?.let { attachVideoTrack(it) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                android.util.Log.i("DShare", "PeerConnectionState -> $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        // Recovered from a transient DISCONNECTED (or this is the first
                        // real connect) - either way, any pending grace-period teardown
                        // is now stale.
                        disconnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        disconnectTimeoutRunnable = null
                        if (!connectedNotified) {
                            connectedNotified = true
                            callbacks.onRemoteConnected()
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        // Don't tear down immediately - give ICE a chance to recover on
                        // its own, which it very often does within a second or two.
                        disconnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        val runnable = Runnable {
                            android.util.Log.i("DShare", "DISCONNECTED did not recover within grace period, closing")
                            callbacks.onConnectionClosed()
                        }
                        disconnectTimeoutRunnable = runnable
                        mainHandler.postDelayed(runnable, DISCONNECT_GRACE_MS)
                    }
                    PeerConnection.PeerConnectionState.CLOSED,
                    PeerConnection.PeerConnectionState.FAILED -> {
                        disconnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        disconnectTimeoutRunnable = null
                        callbacks.onConnectionClosed()
                    }
                    else -> {}
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                android.util.Log.i("DShare", "SignalingState -> $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                android.util.Log.i("DShare", "IceConnectionState -> $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                android.util.Log.i("DShare", "IceGatheringState -> $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: return

        peerConnection = pc

        android.util.Log.i("DShare", "received offer:\n$sdp")
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                android.util.Log.i("DShare", "remote description (offer) set, creating answer")
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        android.util.Log.i("DShare", "answer created:\n${desc.description}")
                        pc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                android.util.Log.i("DShare", "local description (answer) set, sending answer")
                                callbacks.onAnswerCreated(desc.description)
                            }
                            override fun onSetFailure(error: String?) {
                                android.util.Log.e("DShare", "setLocalDescription(answer) failed: $error")
                            }
                        }, desc)
                    }
                    override fun onCreateFailure(error: String?) {
                        android.util.Log.e("DShare", "createAnswer failed: $error")
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) {
                android.util.Log.e("DShare", "setRemoteDescription(offer) failed: $error")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        android.util.Log.i("DShare", "remote ICE candidate: $candidate")
        val pc = peerConnection
        if (pc == null) {
            android.util.Log.e("DShare", "addRemoteIceCandidate: no PeerConnection yet, dropping candidate")
            return
        }
        pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate), object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() {
                android.util.Log.i("DShare", "remote ICE candidate added successfully")
            }
            override fun onAddFailure(error: String?) {
                android.util.Log.e("DShare", "remote ICE candidate add FAILED: $error")
            }
        })
    }

    fun close() {
        disconnectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnectTimeoutRunnable = null
        attachedTrack?.removeSink(renderer)
        attachedTrack = null
        peerConnection?.close()
        peerConnection = null
        renderer.clearImage()
    }

    fun release() {
        close()
        renderer.release()
        factory.dispose()
        eglBase.release()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
