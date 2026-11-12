package dev.brahmkshatriya.echo.extension

import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Asks the Android media session of the running Echo app to move to the next
 * track.
 *
 * Echo does not expose a playback-control API to extensions, so this uses the
 * media-button route: the application context is resolved reflectively and a
 * `KEYCODE_MEDIA_NEXT` key event is dispatched through `AudioManager`. Media
 * key events are delivered to the active media session (the Echo player) and
 * do not require any special permission.
 */
class PlaybackSkipper {

    private val lock = Any()

    @Volatile
    private var audioManager: Any? = null

    @Volatile
    private var dispatchMethod: Method? = null

    @Volatile
    private var keyEventConstructor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var lastResolutionAttempt = 0L

    /**
     * Dispatches a "next track" media key. Returns false if the playback
     * session could not be reached.
     */
    fun skipNext(): Boolean = synchronized(lock) {
        repeat(2) { attempt ->
            val manager = audioManager ?: resolveAudioManager()
            val method = dispatchMethod
            val constructor = keyEventConstructor
            if (manager == null || method == null || constructor == null) {
                if (attempt == 0) {
                    // The application may not have been created yet when a
                    // very early callback arrives. Allow one immediate retry.
                    lastResolutionAttempt = 0L
                    return@repeat
                }
                return false
            }

            return try {
                method.invoke(manager, constructor.newInstance(ACTION_DOWN, KEYCODE_MEDIA_NEXT))
                method.invoke(manager, constructor.newInstance(ACTION_UP, KEYCODE_MEDIA_NEXT))
                true
            } catch (throwable: Throwable) {
                System.err.println("Deezer auto-skip failed: ${throwable.message}")
                audioManager = null
                dispatchMethod = null
                keyEventConstructor = null
                false
            }
        }
        false
    }

    private fun resolveAudioManager(): Any? {
        val now = System.currentTimeMillis()
        if (now - lastResolutionAttempt < RESOLUTION_RETRY_MS) return null
        lastResolutionAttempt = now

        return try {
            val context = resolveApplication() ?: return null
            val audioManager = Class.forName("android.content.Context")
                .getMethod("getSystemService", String::class.java)
                .invoke(context, "audio")
                ?: return null
            val keyEventClass = Class.forName("android.view.KeyEvent")
            val dispatch = Class.forName("android.media.AudioManager")
                .getMethod("dispatchMediaKeyEvent", keyEventClass)
            val constructor = keyEventClass.getConstructor(Integer.TYPE, Integer.TYPE)

            this.audioManager = audioManager
            dispatchMethod = dispatch
            keyEventConstructor = constructor
            audioManager
        } catch (throwable: Throwable) {
            System.err.println("Deezer auto-skip could not resolve the audio service: ${throwable.message}")
            null
        }
    }

    private fun resolveApplication(): Any? {
        // Public hidden API that is still callable on all supported Android
        // versions (annotated @UnsupportedAppUsage, not blocked).
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null)?.let { return it }
        }

        runCatching {
            Class.forName("android.app.AppGlobals")
                .getMethod("getInitialApplication").invoke(null)?.let { return it }
        }

        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getApplication").invoke(thread)?.let { return it }
        }

        // Best effort for non-minified builds: ask Koin for the Application
        // instance that Echo registers through androidContext().
        runCatching {
            val globalContextClass = Class.forName("org.koin.core.context.GlobalContext")
            val globalContext = globalContextClass.getField("INSTANCE").get(null)
            val koin = globalContextClass.methods
                .firstOrNull { it.name == "get" && it.parameterCount == 0 }
                ?.invoke(globalContext)
                ?: return@runCatching
            val get = koin.javaClass.methods.firstOrNull { method ->
                method.name == "get" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0] == KClass::class.java
            }
            // Echo registers its context as Context in Koin via androidContext().
            val contextClass = Class.forName("android.content.Context")
            get?.invoke(koin, contextClass.kotlin, null, null)?.let { return it }
        }

        return null
    }

    private companion object {
        private const val KEYCODE_MEDIA_NEXT = 87
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val RESOLUTION_RETRY_MS = 5_000L
    }
}
