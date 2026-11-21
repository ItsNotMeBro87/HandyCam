package com.example.handycam

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Wrappers around platform APIs that are newer than Android 5.0 (API 21).
 *
 * Every API-level specific class lives in its own nested object so that the classes that are
 * missing on an old device are only ever loaded on devices that actually have them.
 * StreamService calls these helpers instead of touching the new classes directly.
 */
internal object ApiCompat {

    // ---------------------------------------------------------------- Camera2 sessions

    /**
     * Creates a Camera2 capture session with the best API available on this device:
     *  - API 28+: SessionConfiguration (physical lens ids, session parameters, HDR profile)
     *  - API 23-27: createConstrainedHighSpeedCaptureSession / createCaptureSession(List)
     *  - API 21-22: createCaptureSession(List)
     */
    @Suppress("DEPRECATION")
    fun createCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        highSpeed: Boolean,
        physicalCameraId: String?,
        hdrSurface: Surface?,
        sessionParameters: CaptureRequest?,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28.createSession(
                camera, surfaces, highSpeed, physicalCameraId, hdrSurface,
                sessionParameters, callback, handler
            )
            return
        }
        if (highSpeed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Api23.createHighSpeedSession(camera, surfaces, callback, handler)
                return
            }
            throw IllegalStateException("High frame rate capture needs Android 6.0 or newer")
        }
        camera.createCaptureSession(surfaces, callback, handler)
    }

    /** Submits a repeating request, using burst mode for constrained high-speed sessions. */
    fun submitRepeating(session: CameraCaptureSession, request: CaptureRequest, handler: Handler?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Api23.submitHighSpeedRepeating(session, request, handler)
        ) {
            return
        }
        session.setRepeatingRequest(request, null, handler)
    }

    // ---------------------------------------------------------------- HDR (Android 13+)

    fun supportsHlg10(chars: CameraCharacteristics): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Api33.supportsHlg10(chars)

    fun enableHdrEditing(format: MediaFormat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29.enableHdrEditing(format)
        }
    }

    // ---------------------------------------------------------------- Device audio (Android 10+)

    /** Whether "stream device audio while casting the screen" is possible on this device. */
    val supportsPlaybackCapture: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Only call when [supportsPlaybackCapture] is true. */
    fun createPlaybackCaptureRecord(
        projection: MediaProjection,
        sampleRate: Int,
        channelMask: Int,
        bufferSize: Int
    ): AudioRecord {
        check(supportsPlaybackCapture) { "Device audio capture needs Android 10" }
        return Api29.createPlaybackCaptureRecord(projection, sampleRate, channelMask, bufferSize)
    }

    // ================================================================ per-API holders

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28 {
        fun createSession(
            camera: CameraDevice,
            surfaces: List<Surface>,
            highSpeed: Boolean,
            physicalCameraId: String?,
            hdrSurface: Surface?,
            sessionParameters: CaptureRequest?,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {
            val outputs = surfaces.map { surface ->
                OutputConfiguration(surface).also { output ->
                    if (physicalCameraId != null) output.setPhysicalCameraId(physicalCameraId)
                    if (hdrSurface != null && surface == hdrSurface &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        Api33.applyHlg10(output)
                    }
                }
            }
            val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED
            else SessionConfiguration.SESSION_REGULAR
            val executor = Executor { command -> handler?.post(command) }
            val config = SessionConfiguration(type, outputs, executor, callback)
            if (sessionParameters != null) config.setSessionParameters(sessionParameters)
            camera.createCaptureSession(config)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private object Api23 {
        fun createHighSpeedSession(
            camera: CameraDevice,
            surfaces: List<Surface>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {
            camera.createConstrainedHighSpeedCaptureSession(surfaces, callback, handler)
        }

        /** Returns true if [session] was a high-speed session and the request was submitted. */
        fun submitHighSpeedRepeating(
            session: CameraCaptureSession,
            request: CaptureRequest,
            handler: Handler?
        ): Boolean {
            if (session is CameraConstrainedHighSpeedCaptureSession) {
                session.setRepeatingBurst(session.createHighSpeedRequestList(request), null, handler)
                return true
            }
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun enableHdrEditing(format: MediaFormat) {
            format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
        }

        @SuppressLint("MissingPermission")
        fun createPlaybackCaptureRecord(
            projection: MediaProjection,
            sampleRate: Int,
            channelMask: Int,
            bufferSize: Int
        ): AudioRecord {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            return AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun supportsHlg10(chars: CameraCharacteristics): Boolean {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            return profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        }

        fun applyHlg10(output: OutputConfiguration) {
            output.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
    }
}
