package com.firestream.chat.data.share

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedContentHolder @Inject constructor() {
    var pendingIntent: Intent? = null

    fun consumeIntent(): Intent? = pendingIntent.also { pendingIntent = null }
}
