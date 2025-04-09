package dev.chalo.scrcast

import android.net.Uri;
import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import java.io.IOException
import android.os.Handler
import android.os.Looper
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.launch
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.provider.MediaStore
import android.os.Environment
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.CompositeMultiplePermissionsListener
import com.karumi.dexter.listener.multi.DialogOnAnyDeniedMultiplePermissionsListener
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import dev.chalo.scrcast.config.Options
import dev.chalo.scrcast.internal.config.dsl.OptionsBuilder
import dev.chalo.scrcast.extensions.supportsPauseResume
import dev.chalo.scrcast.internal.recorder.*
import dev.chalo.scrcast.recorder.*
import dev.chalo.scrcast.recorder.RecordingState.*
import dev.chalo.scrcast.recorder.RecordingStateChangeCallback
import dev.chalo.scrcast.recorder.notification.NotificationProvider
import dev.chalo.scrcast.internal.recorder.notification.RecorderNotificationProvider
import dev.chalo.scrcast.internal.recorder.service.RecorderService
import dev.chalo.scrcast.internal.request.RecordScreen
import java.io.File

/**
 * Main Interface for accessing [ScrCast] Library
 */
class ScrCast private constructor(private val activity: ComponentActivity) {


    interface PermissionCallback {
        fun onPermissionResult(success: Boolean, message: String?)
    }


    interface RecordingCallback {
        fun onRecordingResult(success: Boolean, message: String)
    }

    private var permissionCallback: PermissionCallback? = null
    private var recordingCallback: RecordingCallback? = null

    fun setRecordingCallback(callback: RecordingCallback) {
        recordingCallback = callback
    }

    /**
     * The current [RecordingState] of the recorder
     *
     * @see [RecordingState]
     */
    var state: RecordingState = Idle()
        private set(value) {
            val was = field
            field = value
            onStateChange?.invoke(value)
            if (was == Recording && value is Idle) {
                try {
                    broadcaster.unregisterReceiver(recordingStateHandler)
                } catch (swallow: Exception) {
                }

                activity.unbindService(connection)
                activity.stopService(recordingSession)
                scanForOutputFile()
            }
        }

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as RecorderService.LocalBinder
            serviceBinder = binder.service
            serviceBinder?.setNotificationProvider(
                notificationProvider ?: defaultNotificationProvider
            )
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            serviceBinder = null
        }
    }

    private var recordingSession: Intent? = null

    private val dialogPermissionListener: DialogOnAnyDeniedMultiplePermissionsListener =
        DialogOnAnyDeniedMultiplePermissionsListener.Builder
            .withContext(activity)
            .withTitle("Storage permissions")
            .withMessage("Storage permissions are needed to store the screen recording")
            .withButtonText(android.R.string.ok)
            .withIcon(R.drawable.ic_storage_permission_dialog)
            .build()

    private val defaultNotificationProvider by lazy {
        RecorderNotificationProvider(
            activity,
            options.notification
        )
    }
    private var notificationProvider: NotificationProvider? = null

    private var onStateChange: RecordingStateChangeCallback? = null
    private var onRecordingOutput: RecordingOutputFileCallback? = null

    private val metrics by lazy {
        DisplayMetrics().apply { activity.windowManager.defaultDisplay.getMetrics(this) }
    }

    private val dpi by lazy { metrics.density }

    /**
     * Current [Options] for this instance.
     *
     * Available for client read/write persistence for user defaults.
     *
     * @see [options]
     * @see [updateOptions]
     * @see [Options]
     */
    var options = Options()
        private set

    private var serviceBinder: RecorderService? = null

    private val broadcaster by lazy {
        LocalBroadcastManager.getInstance(activity)
    }

    private val recordingStateHandler = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            p1?.action?.let { action ->
                when (action) {
                    STATE_RECORDING -> state = Recording
                    STATE_IDLE -> state = Idle(p1.extras?.get(EXTRA_ERROR) as? Throwable)
                    STATE_DELAY -> {
                        state = Delay(p1.extras?.getInt(EXTRA_DELAY_REMAINING) ?: 0)
                    }

                    STATE_PAUSED -> state = Paused
                }
            }
        }
    }

    private val outputDirectory: File?
        get() = options.storage.mediaStorageLocation

    private var _outputFile: File? = null
    private val outputFile: File?
        get() {
            if (_outputFile == null) {
                outputDirectory?.let { dir ->
                    _outputFile =
                        File("${dir.path}${File.separator}${options.storage.fileNameFormatter()}.mp4")
                } ?: return null
            }
            return _outputFile
        }

    private val permissionListener = object : MultiplePermissionsListener {
        override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
            if (report != null && report.areAllPermissionsGranted()) {
                Log.d("ScrCast", "All permissions granted, starting recording")
                permissionCallback?.onPermissionResult(true, null)
                startRecording()
            } else {
                Log.d("ScrCast", "Permissions not granted, recording cancelled")
                permissionCallback?.onPermissionResult(false, "Storage permission denied")
            }
        }

        override fun onPermissionRationaleShouldBeShown(
            permissions: MutableList<PermissionRequest>?,
            token: PermissionToken?
        ) {
            Log.d("ScrCast", "Permission rationale should be shown")
            token?.continuePermissionRequest()
        }
    }

    private val startRecording = activity.registerForActivityResult(
        RecordScreen()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (options.moveTaskToBack) activity.moveTaskToBack(true)
            val output = outputFile
            if (output != null) {
                startService(result, output)
                recordingCallback?.onRecordingResult(true, "Recording started successfully.")
            } else {
                recordingCallback?.onRecordingResult(false, "Output file is null.")
            }
        } else {
            recordingCallback?.onRecordingResult(false, "Screen recording permission denied.")
        }
    }

    /**
     * Updates the configurations of [ScrCast] via a DSL.
     *
     * @see [Options]
     *
     * This method is not accessible to the JVM.
     */
    @JvmSynthetic
    fun options(opts: OptionsBuilder.() -> Unit) {
        options = handleDynamicVideoSize(OptionsBuilder().apply(opts).build())
    }

    /**
     * Updates the configurations of [ScrCast].
     *
     * @see [Options]
     */
    fun updateOptions(options: Options) {
        this.options = handleDynamicVideoSize(options)
    }

    /**
     * Set the recording callbacks, emitting changes of [RecordingState] as they occur and a link to the output [File]
     */
    fun setRecordingCallback(listener: RecordingCallbacks?) {
        onStateChange = { listener?.onStateChange(it) }
        onRecordingOutput = { listener?.onRecordingFinished(it) }
    }

    /**
     * Set an explicit state change listener, as a kotlin lambda, emitting changes of [RecordingState] as they occur.
     *
     * This is an alternative to providing the combined [RecordingCallbacks] if you are only interested in state changes or
     * want to define them independently.
     *
     * This method is not accessible to the JVM.
     */
    @JvmSynthetic
    fun onRecordingStateChange(callback: RecordingStateChangeCallback) {
        onStateChange = callback
    }

    /**
     * Set an explicit output file listener, as a kotlin lambda.
     *
     * This is an alternative to providing the combined [RecordingCallbacks] if you are only interested in the output file or
     * want to define them independently.
     *
     * This method is not accessible to the JVM.
     */
    fun onRecordingComplete(callback: RecordingOutputFileCallback) {
        onRecordingOutput = callback
    }

    /**
     * Convenience method for clients to easily check if the required permissions are enabled for storage
     * Even though we internally will bubble up the permission request and handle the allow/deny,
     * some clients may want to onboard users via an OOBE or some UX state involving previously recorded files.
     */
    fun hasStoragePermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        return perms.all {
            ActivityCompat.checkSelfPermission(
                activity,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Set the [NotificationProvider] for the [ScrCast] instance.
     *
     * @see [NotificationProvider]
     */
    fun setNotificationProvider(provider: NotificationProvider) {
        notificationProvider = provider
    }

    /**
     * Triggers a recording session based on the configuration's defined by [options]
     *
     * @see [updateOptions]
     * @see [Options]
     * @see [MediaRecorder.start]
     */
    fun record(callback: PermissionCallback? = null) {
        permissionCallback = callback
        when (state) {
            is Idle -> {
                // Only check for storage permissions on devices before Android 14
                if (Build.VERSION.SDK_INT < 29 && hasStoragePermissions()) {
                    Log.d("ScrCast", "Permissions already granted, starting recording")
                    startRecording()
                } else if (Build.VERSION.SDK_INT < 29) {
                    Log.d("ScrCast", "Requesting storage permissions")
                    Dexter.withContext(activity)
                        .withPermissions(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                        .withListener(
                            CompositeMultiplePermissionsListener(
                                permissionListener,
                                dialogPermissionListener
                            )
                        )
                        .check()
                } else {
                    // For Android 14+, no need to ask for storage permissions
                    Log.d("ScrCast", "Android 14+ detected, starting recording directly")
                    startRecording()
                }
            }

            Paused -> resume()
            Recording -> stopRecording()
            is Delay -> { /* Prevent erroneous calls to record while in start delay */
            }
        }
    }

    /**
     * Triggers the end to a recording session that was started via [record]
     *
     * @see [MediaRecorder.stop]
     */
    fun stopRecording() {
        broadcaster.sendBroadcast(Intent(Action.Stop.name))
    }

    /**
     * Pauses a recording session that was started via [record]
     *
     * This only invokes a change to the recording state if the target device
     * is [Build.VERSION_CODES.N] or higher.
     *
     * @see [MediaRecorder.pause]
     */
    fun pause() {
        if (supportsPauseResume) {
            if (state.isRecording) {
                broadcaster.sendBroadcast(Intent(Action.Pause.name))
            }
        }
    }

    /**
     * Resumed a recording session that was paused via [pause], or triggers a new recording
     * if the [state] is not paused.
     *
     * * This only invokes a change to the recording state if the target device
     * is [Build.VERSION_CODES.N] or higher.
     *
     * @see [MediaRecorder.resume]
     */
    fun resume() {
        if (supportsPauseResume) {
            if (state.isPaused) {
                broadcaster.sendBroadcast(Intent(Action.Resume.name))
            } else {
                record(null)
            }
        }
    }

    private fun handleDynamicVideoSize(options: Options): Options {
        var reconfig: Options = options
        if (options.video.width == -1) {
            reconfig = reconfig.copy(video = reconfig.video.copy(width = metrics.widthPixels))
        }
        if (options.video.height == -1) {
            reconfig = reconfig.copy(video = reconfig.video.copy(height = metrics.heightPixels))
        }
        return reconfig
    }

    /*
    private fun scanForOutputFile() {
        MediaScannerConnection.scanFile(
            activity,
            arrayOf(outputFile.toString()),
            null
        ) { path, uri ->
            Log.i("scrcast", "scanned: $path")
            Log.i("scrcast", "-> uri=$uri")
            onRecordingOutput?.invoke(File(path))
            _outputFile = null
        }
    }
    */
    private fun scanForOutputFile() {
        val file = outputFile
        Log.d("scrcast", "File path: ${file?.absolutePath}")
        Log.d("scrcast", "File exists: ${file?.exists()}")
        Log.d("scrcast", "File size: ${file?.length()}")
        if (file == null) {
            Log.e("scrcast", "Output file is null")
            return
        }

        /*
        if (!file.exists()) {
            Log.e("scrcast", "File does not exist")
            return
        }

        if (file.length() == 0L) {
            Log.e("scrcast", "File exists but is empty")
            return
        }
        */

        MediaScannerConnection.scanFile(
            activity,
            arrayOf(outputFile.toString()),
            arrayOf("video/mp4")
        ) { path, uri ->
            Log.i("scrcast", "scanned: $path")
            Log.i("scrcast", "-> uri=$uri")

            if (uri != null) {
                // Safe to show Snackbar now, file is visible in Gallery
                onRecordingOutput?.invoke(File(path))
            } else {
                // Fallback: wait 1-2 seconds and retry?
                Log.w("scrcast", "URI was null — retrying scan after delay")

                Handler(Looper.getMainLooper()).postDelayed({
                    MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(outputFile.toString()),
                    arrayOf("video/mp4")
                ) { retryPath, retryUri ->
                    Log.i("scrcast", "Retry scanned: $retryPath")
                    Log.i("scrcast", "-> retry uri=$retryUri")

                    onRecordingOutput?.invoke(File(retryPath))
                }
                }, 1500)
            }

            _outputFile = null
        }
    }

    private fun startRecording() {
        startRecording.launch()
    }

    private fun saveToMediaStore(): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ScreenRecordings") // Saves under DCIM
        }

       val resolver = activity.contentResolver
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry")
    }



    private fun startService(result: ActivityResult, file: File) {
        val outputUri: Uri? = if (Build.VERSION.SDK_INT >= 29) {
                saveToMediaStore()  // Use MediaStore on Android 14+
        } else {
                Uri.fromFile(file)  // Use traditional storage path for older versions
        }

        if (outputUri == null) {
                recordingCallback?.onRecordingResult(false, "Failed to get output file.")
                return
        }

        // Ensure proper intent passing for Media Projection Service
        recordingSession = Intent(activity, RecorderService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("options", options)
                putExtra("outputUri", outputUri.toString())  // Pass URI instead of absolute path
                putExtra("dpi", dpi)
                putExtra("rotation", activity.windowManager.defaultDisplay.rotation)
        }

        broadcaster.registerReceiver(
                recordingStateHandler,
                IntentFilter().apply {
                        addAction(STATE_IDLE)
                        addAction(STATE_RECORDING)
                        addAction(STATE_PAUSED)
                        addAction(STATE_DELAY)
                }
        )

        activity.bindService(recordingSession, connection, Context.BIND_AUTO_CREATE)
        activity.startService(recordingSession)
    }


    companion object {
        /**
         * Instance creator for [ScrCast].
         *
         * Requires an [Activity] reference for media projection creation, as well
         * as auto video-sizing in [Options].
         *
         * @see [Options.video]
         */
        @JvmStatic
        fun use(activity: ComponentActivity): ScrCast {
            return ScrCast(activity)
        }
    }
}
