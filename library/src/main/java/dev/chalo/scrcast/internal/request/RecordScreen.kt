package dev.chalo.scrcast.internal.request

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY)
class RecordScreen : ActivityResultContract<Void, ActivityResult>() {
    override fun createIntent(context: Context, input: Void?): Intent {
        val pm = context.getSystemService(MediaProjectionManager::class.java)

        return if (pm != null) {
            // Check if the API level is 31 or higher
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Use MediaProjectionConfig for Android 12 (API 31) and above
                pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                // Fallback for lower API levels
                pm.createScreenCaptureIntent()
            }
        } else {
            // Handle the case where MediaProjectionManager is not available
            throw IllegalStateException("MediaProjectionManager is not available")
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult {
        return ActivityResult(resultCode, intent)
    }
}







