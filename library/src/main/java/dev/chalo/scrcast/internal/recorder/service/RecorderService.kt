package dev.chalo.scrcast.internal.recorder.service

import dev.chalo.scrcast.MyApp
import dev.chalo.scrcast.R
import android.view.SurfaceView
import android.view.Surface
import android.os.ParcelFileDescriptor;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import android.os.Environment
import androidx.annotation.RequiresApi
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri
import java.io.IOException
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaRecorder.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.chalo.scrcast.config.Options
import dev.chalo.scrcast.internal.extensions.countdown
import dev.chalo.scrcast.internal.recorder.*
import dev.chalo.scrcast.recorder.*
import dev.chalo.scrcast.recorder.notification.NotificationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

@RestrictTo(RestrictTo.Scope.LIBRARY)
class RecorderService : Service() {

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private val broadcaster by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    private val binder = LocalBinder()

    private lateinit var notificationProvider: NotificationProvider

    private val pauseResumeHandler = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
                ACTION_STOP -> stopRecording()
            }
        }
    }
    private val screenHandler = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("scrcast", "stopping recording with screen off per request")
                    if (state == RecordingState.Recording) {
                        stopRecording()
                    }
                }
            }
        }
    }

    private var state: RecordingState = RecordingState.Idle()
        set(value) {
            field = value
            broadcaster.sendBroadcast(Intent(value.stateString()).apply {
                if (value is RecordingState.Delay) {
                    putExtra(EXTRA_DELAY_REMAINING, value.remainingSeconds)
                } else if (value is RecordingState.Idle) {
                    putExtra(EXTRA_ERROR, value.error)
                }
            })
        }

    private var options: Options = Options()
    private lateinit var outputFile: String
    private var rotation = 0
    private val orientation by lazy {
        orientations.get(rotation + 90)
    }
    private var dpi: Float = 0f

    private var requestCode: Int = -1
    private var requestData: Intent = Intent()

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback = MediaProjectionCallback()

    private var _virtualDisplay: VirtualDisplay? = null
    private val virtualDisplay: VirtualDisplay?
        get() {
            if (_virtualDisplay == null) {
                val displayMetrics = Resources.getSystem().displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val density = displayMetrics.densityDpi
                val surface = getAppSurface() // Get the app's surface for recording

                _virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AppScreenRecording",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }
            return _virtualDisplay
        }

    private var mediaRecorder: MediaRecorder? = null

    /**
    * Returns the Surface of the current app window.
    * Use a TextureView, SurfaceView, or other drawable component from the activity.
    */
    private fun getAppSurface(): Surface? {
        val currentActivity = (applicationContext as? MyApp)?.currentActivity
        return if (currentActivity != null) {
            val surfaceView = currentActivity.findViewById<SurfaceView>(R.id.surface_view)
            surfaceView?.holder?.surface ?: run {
                Log.e("RecorderService", "SurfaceView is null or not available.")
                null
            }
        } else {
            Log.e("RecorderService", "No current activity found!")
            null
        }
    }

    private fun createRecorder() {

        Log.d("scrcast", "createRecorder()")
        mediaRecorder?.release()
        mediaRecorder = null
        try{
            val fileDescriptor: FileDescriptor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val videoUri = saveToMediaStore(this)
                contentResolver.openFileDescriptor(videoUri, "w")?.fileDescriptor
            } else {
                val legacyFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "ScreenRecord_${System.currentTimeMillis()}.mp4"
                )
                legacyFile.parentFile?.mkdirs()
                legacyFile.outputStream().fd
            }

            if (fileDescriptor == null) {
                Log.e("scrcast", "FileDescriptor is null, cannot proceed.")
                return
            }
            val metrics = Resources.getSystem().displayMetrics

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(VideoSource.SURFACE)
                setOutputFormat(options.storage.outputFormat)
                setOutputFile(fileDescriptor)
                setVideoSize(720, 1280)
                with(options.video) {
                    setVideoEncoder(videoEncoder)
                    setVideoEncodingBitRate(bitrate)
                    setVideoFrameRate(30)
                    if (maxLengthSecs > 0) {
                        setMaxDuration(maxLengthSecs * 1000)
                    }
                }
                with(options.storage) {
                    if (maxSizeMB > 0) {
                        setMaxFileSize((maxSizeMB * (1024 * 1024)).toLong())
                    }
                }
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                            Log.d(
                                "scrcast",
                                "max duration of ${options.video.maxLengthSecs} seconds reached. Stopping reconrding..."
                            )
                            stopRecording()
                        }
                        MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> Log.d(
                            "scrcast",
                            "Approaching max file size of ${options.storage.maxSizeMB}MB"
                        )
                        MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                            Log.d(
                                "scrcast",
                                "max file size of ${options.storage.maxSizeMB}MB reached. Stopping reconrding..."
                            )
                            stopRecording()
                        }

                    }
                }
                setOrientationHint(orientation)
            }
            mediaRecorder?.prepare()
        } catch (e: Exception) { 
            Log.e("scrcast", "Error in createRecorder(): ${e.localizedMessage}")
            Log.d("scrcast", Log.getStackTraceString(e));
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecord_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ScreenRecordings") // Saves under DCIM
        }

        val resolver = context.contentResolver
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry")
    }

    fun setNotificationProvider(provider: NotificationProvider) {
        notificationProvider = provider
    }

    private fun pause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (state.isRecording) {
                mediaRecorder?.pause()
            }
            state = RecordingState.Paused

            notificationProvider.update(state)
        }
    }

    private fun resume() {
        when (state) {
            is RecordingState.Idle -> startRecording()
            RecordingState.Paused -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                    state = RecordingState.Recording
                    notificationProvider.update(state)
                }
            }
        }
    }

    private fun startRecording(code: Int = requestCode, data: Intent = requestData) {
        requestCode = code
        requestData = data

        if (options.startDelayMs > 0) {
            options.startDelayMs.countdown(
                repeatMillis = 1_000,
                onTick = { state = RecordingState.Delay((it / 1000).toInt() + 1) },
                after = { recordInternal(code, data) }
            )
        } else {
            recordInternal(code, data)
        }
    }

    private fun recordInternal(code: Int, data: Intent) {
        GlobalScope.launch(Dispatchers.Main) {
            startForeground(
                notificationProvider.getNotificationId(),
                notificationProvider.get(state)
            )
            mediaProjection = projectionManager.getMediaProjection(code, data)

            if (options.stopOnScreenOff) {
                with(IntentFilter(Intent.ACTION_SCREEN_OFF)) {
                    registerReceiver(screenHandler, this)
                }
            }

            with(IntentFilter(ACTION_PAUSE).apply {
                addAction(ACTION_RESUME)
                addAction(ACTION_STOP)
            }) {
                broadcaster.registerReceiver(pauseResumeHandler, this)
            }

            mediaProjection?.registerCallback(mediaProjectionCallback, Handler())
            createRecorder()
            virtualDisplay // touch
            try {
                mediaRecorder?.start()
                state = RecordingState.Recording
                notificationProvider.update(state)
            } catch (e: Exception) {
                stopRecording(e)
            }
        }
    }

    private fun stopRecording(error: Throwable? = null) {
        mediaProjection?.stop()

        state = RecordingState.Idle(error)
        stopForeground(true)
    }

    private fun cleanupProjection() {
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection = null

        _virtualDisplay?.release()

        runCatching {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        }
    }

    private inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("scrcast", "projection on stop")
            cleanupProjection()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                 if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        (this as Activity), // `this` must be an Activity, but `RecorderService` is a Service
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        100
                    )
                }
            }

            intent?.let {
                options = it.getParcelableExtra("options") ?: Options()
                rotation = it.getIntExtra("rotation", 0)
                dpi = it.getFloatExtra("dpi", 0f)
                outputFile = it.getStringExtra("outputFile") ?: ""

                startRecording(
                    code = it.getIntExtra("code", -1),
                    data = it.getParcelableExtra("data") ?: Intent()
                )
            }

            return START_STICKY
    }

    override fun onDestroy() {
        Log.d("scrcast", "onDestroy: service")
        stopRecording()
        if (options.stopOnScreenOff) {
            unregisterReceiver(screenHandler)
        }
        try {
            broadcaster.unregisterReceiver(pauseResumeHandler)
        } catch (swallow: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? = binder

    // Class used for the client Binder.
    inner class LocalBinder : Binder() {
        // Return this instance of MyService so clients can call public methods
        val service: RecorderService
            get() = this@RecorderService
    }
}
