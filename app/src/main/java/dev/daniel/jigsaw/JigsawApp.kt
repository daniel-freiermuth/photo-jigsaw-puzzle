package dev.daniel.jigsaw

import android.app.Application
import dev.daniel.jigsaw.source.Http
import java.io.File

class JigsawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // In cacheDir: Android may clear it under storage pressure; everything there can be downloaded again.
        Http.enableDiskCache(File(cacheDir, "http"))
    }
}
