package dev.stint.app

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.stint.app.client.StintController
import java.util.UUID

/** Activity and the visible timer service share a single connection and mutation queue. */
class StintApplication : Application() {
    val controller by lazy {
        StintController(
            client = androidHttpClient(),
            localStore = AndroidLocalStore(this),
            clock = System::currentTimeMillis,
            newId = { UUID.randomUUID().toString() },
            allowInsecureLoopback = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }
}
