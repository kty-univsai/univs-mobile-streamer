package ai.univs.univsmobilestreamer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit


class WebRTCManager(private val context: Context,
                    private val signalingUrl: String) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var videoTrack: VideoTrack
    private lateinit var surfaceHelper: SurfaceTextureHelper
    private lateinit var eglBase: EglBase
    private lateinit var webSocket: WebSocket
    private var isReconnecting = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        Log.d("WebRTC", "🚀 WebRTCManager starting")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        val eglBase = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        ))

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d("WebRTC", "ICE candidate: ${candidate?.sdp}")
                candidate?.let {
                    val candidateJson = JSONObject().apply {
                        put("candidate", it.sdp)
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                    }

                    val messageJson = JSONObject().apply {
                        put("type", "candidate")
                        put("candidate", candidateJson)
                    }

                    webSocket.send(messageJson.toString())
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                // 생략 가능 (필수 구현만 해도 됨)
            }
            override fun onRemoveStream(stream: MediaStream?) {
                // 필요하면 로그 출력하거나 아무 처리 안 해도 됩니다
            }

            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                TODO("Not yet implemented")
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })!!

        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer = Camera1Enumerator(false).run {
            deviceNames.mapNotNull { name -> if (isBackFacing(name)) createCapturer(name, null) else null }.first()
        }
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer.initialize(surfaceHelper, context, videoSource.capturerObserver)
        videoCapturer.startCapture(1280, 720, 10)

        videoTrack = peerConnectionFactory.createVideoTrack("video", videoSource)
        peerConnection.addTrack(videoTrack)

        initSignaling()
    }

    private fun initSignaling() {
        val client = OkHttpClient()
        val request = Request.Builder().url(signalingUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("WebSocket", "✅ WebSocket opened")
                peerConnection.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peerConnection.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val msg = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", desc.description)
                                }
                                webSocket.send(msg.toString())
                            }

                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, MediaConstraints())
            }


            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRTC", "WebSocket failed: ${t.message}")
                attemptReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w("WebRTC", "WebSocket closed: $reason")
                attemptReconnect()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.getString("type")) {
                    "answer" -> {
                        Log.d("UNIVS WebRTC", "📩 Answer received")

                        val sdp = msg.getString("sdp").replace("\\r\\n", "\r\n")
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

                        peerConnection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {
                                Log.e("WebRTC", "Set remote SDP failed: $error")
                            }

                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, answer)
                    }
                    "candidate" -> {
                        val c = msg.getJSONObject("candidate")
                        val ice = IceCandidate(
                            c.getString("sdpMid"),
                            c.getInt("sdpMLineIndex"),
                            c.getString("candidate")
                        )
                        peerConnection.addIceCandidate(ice)
                    }
                }
            }
        })
    }


    private fun attemptReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        coroutineScope.launch {
            delay(3000)
            initSignaling()
            isReconnecting = false
        }
    }

    fun stop() {
        videoCapturer.stopCapture()
        videoCapturer.dispose()
        surfaceHelper.dispose()
        peerConnection.close()
        peerConnectionFactory.dispose()
        coroutineScope.cancel()
    }
}
