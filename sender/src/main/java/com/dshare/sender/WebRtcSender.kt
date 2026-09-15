package com.dshare.sender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Captures this device's screen (via MediaProjection, through WebRTC's own
 * ScreenCapturerAndroid) and offers it over a fresh PeerConnection - the mirror image of
 * the receiver app's WebRtcReceiver, and using the same lessons learned there: 1080p/30fps
 * caps so the encoder never falls behind (the actual cause of growing lag/stutter, not the
 * network), H.264 preferred for hardware encode, and 'balanced' degradation so transient
 * pressure degrades smoothly instead of in hard steps.
 */
class WebRtcSender(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLocalIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onOfferCreated(sdp: String)
        /** Fired once the PeerConnection actually reaches CONNECTED - the only point at
         *  which media is actually flowing, as opposed to onOfferCreated() which just
         *  means the handshake started. */
        fun onConnected()
        fun onCaptureStopped()
        fun onError(message: String)
    }

    companion object {
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
        private const val CAPTURE_FPS = 30
    }

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // H265 excluded: this stream-webrtc-android build advertises it as a supported
        // encoder codec, but on at least one real device including it in codec
        // negotiation left the offer's video m-line dead ("m=video 0 ... 0", answered
        // "a=inactive" by the receiver) - WebRTC has no standardized RTP/SDP mapping for
        // H265 in the first place, so this fork's support for it is exotic at best.
        val encoderFactory = H265FilteringEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
        val supported = encoderFactory.supportedCodecs
        android.util.Log.i("DShareSender", "encoder factory supported codecs: ${supported.joinToString { it.name }}")
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
    }

    private class H265FilteringEncoderFactory(private val delegate: VideoEncoderFactory) : VideoEncoderFactory {
        private fun isH265(codec: VideoCodecInfo) = codec.name.equals("H265", ignoreCase = true)
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = delegate.createEncoder(info)
        override fun getSupportedCodecs(): Array<VideoCodecInfo> = delegate.supportedCodecs.filterNot(::isH265).toTypedArray()
        override fun getImplementations(): Array<VideoCodecInfo> = delegate.implementations.filterNot(::isH265).toTypedArray()
    }

    /** [permissionData] is the raw Intent returned from the MediaProjection permission
     *  prompt (RESULT_OK). Must only be called once the hosting service has already
     *  called startForeground() - ScreenCapturerAndroid obtains the MediaProjection
     *  internally on startCapture(), which on Android 14+ requires the app to already
     *  be a foreground service of type mediaProjection or it throws a SecurityException. */
    fun startCapture(permissionData: Intent, projectionCallback: MediaProjection.Callback) {
        val cap = ScreenCapturerAndroid(permissionData, projectionCallback)
        capturer = cap

        val source = factory.createVideoSource(cap.isScreencast)
        videoSource = source

        val helper = SurfaceTextureHelper.create("DShareCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        val track = factory.createVideoTrack("dshare_screen", source)
        videoTrack = track

        openPeerConnectionAndOffer(track)
    }

    private fun openPeerConnectionAndOffer(track: VideoTrack) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        var connectedNotified = false

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                android.util.Log.i("DShareSender", "local ICE candidate: ${candidate.sdp}")
                callbacks.onLocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                android.util.Log.i("DShareSender", "PeerConnectionState -> $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    if (!connectedNotified) {
                        connectedNotified = true
                        callbacks.onConnected()
                    }
                } else if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) {
                    callbacks.onCaptureStopped()
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                android.util.Log.i("DShareSender", "SignalingState -> $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                android.util.Log.i("DShareSender", "IceConnectionState -> $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                android.util.Log.i("DShareSender", "IceGatheringState -> $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {}
        })

        if (pc == null) {
            callbacks.onError("PeerConnection을 생성할 수 없습니다.")
            return
        }
        peerConnection = pc

        // addTrack() creates a SEND_RECV transceiver by default and requires finding it
        // afterwards via pc.transceivers - which turned out to never actually match
        // (`it.sender === sender` never succeeded here; the JNI bindings hand back a new
        // RtpSender wrapper object on each access, so reference equality against the
        // addTrack() result never holds). That silently no-opped every attempt to touch
        // the transceiver, including this device's real problem: as SEND_RECV, with no
        // decoder factory ever registered on this PeerConnectionFactory (this app never
        // decodes incoming video), the receive side of the negotiation has no codec
        // capability at all, which corrupted the whole video m-line down to
        // "m=video 0 ... 0" regardless of which encoder codecs were available. Using
        // addTransceiver() instead both returns the real transceiver directly (no lookup
        // needed) and lets the direction be set to SEND_ONLY from the start.
        val transceiver = pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("dshare_stream"))
        )
        val sender = transceiver.sender
        android.util.Log.i("DShareSender", "transceiver direction=${transceiver.direction}")

        val capsCheck = factory.getRtpSenderCapabilities(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        android.util.Log.i(
            "DShareSender",
            "RtpSenderCapabilities(video): " +
                (capsCheck?.codecs?.joinToString { "${it.name}(pt=${it.preferredPayloadType},mime=${it.mimeType})" } ?: "null")
        )
        android.util.Log.i("DShareSender", "track state=${track.state()} enabled=${track.enabled()} id=${track.id()}")

        // A previous attempt here called transceiver.setCodecPreferences() with the raw
        // capability list from getRtpSenderCapabilities() to force H.264 first. On at
        // least one real device that produced a broken offer - the video m-line came out
        // as "m=video 0 UDP/TLS/RTP/SAVPF 0" (port 0, payload type 0), which the receiver
        // correctly answered as "a=inactive": a dead media section that never leaves ICE
        // CHECKING, matching the "stuck on connecting" reports. DefaultVideoEncoderFactory
        // already prefers a hardware-backed encoder among whatever codec actually gets
        // negotiated, so default negotiation is used instead of hand-picking a codec list.
        //
        // Also temporarily removed: sender.setParameters() for bitrate/degradation
        // preference, called here before any SDP has ever been negotiated (the sender
        // has zero RtpEncodingParameters at this point). Testing whether that pre-offer
        // setParameters() call is what's corrupting the generated offer.

        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                android.util.Log.i("DShareSender", "offer created:\n${desc.description}")
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        android.util.Log.i("DShareSender", "local description set, sending offer")
                        callbacks.onOfferCreated(desc.description)
                    }

                    override fun onSetFailure(error: String?) {
                        android.util.Log.e("DShareSender", "setLocalDescription failed: $error")
                        callbacks.onError("연결 설정에 실패했습니다: $error")
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                android.util.Log.e("DShareSender", "createOffer failed: $error")
                callbacks.onError("연결 요청 생성에 실패했습니다: $error")
            }
        }, MediaConstraints())
    }

    fun setRemoteAnswer(sdp: String) {
        android.util.Log.i("DShareSender", "received answer:\n$sdp")
        peerConnection?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    android.util.Log.i("DShareSender", "remote description (answer) set successfully")
                }
                override fun onSetFailure(error: String?) {
                    android.util.Log.e("DShareSender", "setRemoteDescription(answer) failed: $error")
                    callbacks.onError("응답 처리에 실패했습니다: $error")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        android.util.Log.i("DShareSender", "remote ICE candidate: $candidate")
        val pc = peerConnection
        if (pc == null) {
            android.util.Log.e("DShareSender", "addRemoteIceCandidate: no PeerConnection yet, dropping candidate")
            return
        }
        pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate), object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() {
                android.util.Log.i("DShareSender", "remote ICE candidate added successfully")
            }
            override fun onAddFailure(error: String?) {
                android.util.Log.e("DShareSender", "remote ICE candidate add FAILED: $error")
            }
        })
    }

    fun stopCapture() {
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            // May already be stopped (e.g. system revoked the projection first).
        }
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        peerConnection?.close()
        peerConnection = null
    }

    fun release() {
        stopCapture()
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
