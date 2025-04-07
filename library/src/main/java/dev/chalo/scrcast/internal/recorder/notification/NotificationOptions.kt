package dev.chalo.scrcast.internal.recorder.notification

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NotificationOptions(
    val title: String = "Screen Recording",
    val content: String = "Recording in progress",
    val iconResId: Int = android.R.drawable.ic_btn_speak_now // example icon
) : Parcelable
