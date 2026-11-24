package app.freeclip

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small on-device log (last ~200 KB) so a real error can be read and shared instead of guessed at. */
object Diag {
    private fun file(ctx: Context) = File(ctx.filesDir, "clipforge_log.txt")
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun version(ctx: Context): String = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName} (build ${p.longVersionCode})"
    } catch (_: Exception) { "?" }

    fun log(ctx: Context, msg: String, t: Throwable? = null) {
        try {
            val f = file(ctx)
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append("  ").append(msg).append('\n')
            if (t != null) {
                sb.append("    ").append(t.javaClass.name).append(": ").append(t.message).append('\n')
                t.stackTrace.take(8).forEach { sb.append("      at ").append(it).append('\n') }
                t.cause?.let { sb.append("    caused by ").append(it.javaClass.name).append(": ").append(it.message).append('\n') }
            }
            f.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): String = try {
        val f = file(ctx)
        val header = "ClipForge AI log, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n"
        header + if (f.exists()) f.readText().takeLast(60_000) else "(empty)"
    } catch (_: Exception) {
        "(could not read the log)"
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
