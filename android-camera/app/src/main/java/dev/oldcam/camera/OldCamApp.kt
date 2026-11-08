package dev.oldcam.camera

import android.app.Application

/**
 * Classe Application: criada uma única vez quando o processo do app sobe,
 * antes de qualquer Activity/Service. Bom lugar para inicializações globais.
 * Registrada no AndroidManifest (android:name=".OldCamApp").
 */
class OldCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
