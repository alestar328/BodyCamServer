package com.falconone.bodycamserver

import android.content.Context
import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration.VideoDimensions

private const val TAG = "FalconLive"

const val AGORA_APP_ID  = "ff51540c357447f7bf060b3150bf6a3e"
const val AGORA_CHANNEL = "falcon_group_channel"
const val BODYCAM_UID   = 9001

object LivestreamService {

    @Volatile var isStreaming = false
        private set

    private var engine: RtcEngine? = null

    fun start(context: Context): Boolean {
        if (isStreaming) return true
        // Camera2 is in use — can't stream and record simultaneously
        if (RecordingActivity.isRecording) return false

        return try {
            val handler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                    Log.d(TAG, "Joined $channel uid=$uid")
                    isStreaming = true
                    HardwareController.ledBlue()
                }
                override fun onLeaveChannel(stats: IRtcEngineEventHandler.RtcStats?) {
                    Log.d(TAG, "Left channel")
                    isStreaming = false
                }
                override fun onError(err: Int) {
                    Log.e(TAG, "Agora error code: $err")
                }
            }

            val config = RtcEngineConfig().apply {
                mAppId     = AGORA_APP_ID
                mContext   = context
                mEventHandler = handler
            }

            val eng = RtcEngine.create(config)
            engine = eng

            eng.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            eng.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            eng.enableVideo()

            // Bodycam sensor is 90° — use rear camera and let Agora auto-detect orientation
            eng.setCameraCapturerConfiguration(
                CameraCapturerConfiguration(CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR)
            )
            eng.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoDimensions(640, 480),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    700,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                )
            )

            val options = ChannelMediaOptions().apply {
                channelProfile         = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType         = Constants.CLIENT_ROLE_BROADCASTER
                publishCameraTrack     = true
                publishMicrophoneTrack = false  // audio goes via BT, not Agora
                autoSubscribeVideo     = false  // bodycam only sends, doesn't receive
                autoSubscribeAudio     = false
            }

            // null token — only works if App Certificate is NOT enabled in Agora console.
            // If you see error code 101/110, enable "No Auth" in the Agora project settings.
            eng.joinChannel(null, AGORA_CHANNEL, BODYCAM_UID, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            engine?.leaveChannel()
            RtcEngine.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "stop: ${e.message}")
        } finally {
            engine = null
            isStreaming = false
            HardwareController.ledGreen()
            Log.d(TAG, "Livestream stopped")
        }
    }
}
