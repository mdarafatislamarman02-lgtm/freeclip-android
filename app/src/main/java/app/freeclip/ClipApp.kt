package app.freeclip

import android.app.Application

/** Owns the one MainVM for the whole process, so a running job is not tied to the screen being open. */
class ClipApp : Application() {
    val vm: MainVM by lazy { MainVM(this) }
}
