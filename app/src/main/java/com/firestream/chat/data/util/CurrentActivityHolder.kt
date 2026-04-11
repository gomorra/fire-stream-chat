package com.firestream.chat.data.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently-resumed Activity so singletons can reach a real
 * Activity context (e.g. to host a short-lived Dialog for offscreen
 * WebView rendering). The reference is weak so a leaked holder can never
 * pin an Activity.
 */
@Singleton
class CurrentActivityHolder @Inject constructor() {

    @Volatile
    private var ref: WeakReference<Activity>? = null

    val current: Activity? get() = ref?.get()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                ref = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (ref?.get() === activity) ref = null
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (ref?.get() === activity) ref = null
            }
        })
    }
}
