package dev.chalo.scrcast.internal.request
import android.app.Activity
import android.content.Context
import android.os.Build
import android.content.Intent
import android.media.projection.MediaProjectionManager
  /* import android.media.projection.MediaProjectionConfig  */
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RestrictTo
@RestrictTo(RestrictTo.Scope.LIBRARY)

class RecordScreen : ActivityResultContract<Void, ActivityResult>() {
    override fun createIntent(context: Context, input: Void?): Intent {
        val pm = context.getSystemService(MediaProjectionManager::class.java)
         return pm.createScreenCaptureIntent()
        /*
        if (Build.VERSION.SDK_INT >= 35) {
            return pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
          return pm.createScreenCaptureIntent()
        }
        */
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult {
        return ActivityResult(resultCode, intent)
    }
}
