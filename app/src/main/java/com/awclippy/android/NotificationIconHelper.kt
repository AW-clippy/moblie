package com.awclippy.android

import android.content.Context

object NotificationIconHelper {
    
    /**
     * Returns the resource ID for the notification small icon.
     * Android requires notification small icons to be drawable resources,
     * not runtime-created bitmaps.
     */
    fun getNotificationIcon(): Int {
        return R.drawable.ic_notification
    }
}
