package app.freeclip

import android.app.Application

/** Owns the one MainVM for the whole process, so a running job is not tied to the screen being open. */
class ClipApp : Application() {
    val vm: MainVM by lazy { MainVM(this) }

    override fun onCreate() {
        super.onCreate()
        // A new process has no running job, so any leftover progress notification is stale.
        ClipService.clearAll(this)
    }
}
