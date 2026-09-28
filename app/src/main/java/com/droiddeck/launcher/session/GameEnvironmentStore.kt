package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

object GameEnvironmentStore {
    private const val SETTINGS = "game-environment.json"
    private const val GUEST_FILE = "root/.config/droiddeck/game-environment.json"

    @Synchronized
    fun read(context: Context): GameEnvironment.Config {
        val file = AtomicFile(File(context.filesDir, SETTINGS))
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return GameEnvironment.Config() }
        return decode(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    fun decode(json: JSONObject): GameEnvironment.Config {
        require(json.getInt("version") == 1)
        fun entries(value: JSONObject?): Map<String, String?> = buildMap {
            value?.keys()?.forEach { name ->
                require(GameEnvironment.validName(name))
                val entry = value.get(name)
                require(entry == JSONObject.NULL || entry is String && GameEnvironment.validValue(entry))
                put(name, if (entry == JSONObject.NULL) null else entry as String)
            }
        }
        val games = json.optJSONObject("games")
        return GameEnvironment.Config(entries(json.optJSONObject("shared")), buildMap {
            games?.keys()?.forEach { id ->
                require(id.isNotEmpty() && GameEnvironment.validScope(id))
                put(id, entries(games.getJSONObject(id)))
            }
        })
    }

    fun encode(config: GameEnvironment.Config): JSONObject {
        fun entries(values: Map<String, String?>) = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        return JSONObject().put("version", 1).put("shared", entries(config.shared)).put("games", JSONObject().apply {
            config.games.forEach { (id, values) -> put(id, entries(values)) }
        })
    }

    @Synchronized
    fun save(context: Context, config: GameEnvironment.Config) {
        val json = encode(config)
        decode(json)
        write(File(context.filesDir, SETTINGS), json.toString())
        publish(context, config)
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * [save] on one app-wide thread, so edits land in the order made and outlive the page that made
     * them. On failure [onFailure] gets what is on disk, or null when that cannot be read either.
     */
    fun saveLater(context: Context, config: GameEnvironment.Config, onFailure: (GameEnvironment.Config?) -> Unit) {
        val app = context.applicationContext
        writer.execute {
            val error = runCatching { save(app, config) }.exceptionOrNull() ?: return@execute
            android.util.Log.e("GameEnvironment", "Could not save game environment", error)
            val stored = runCatching { read(app) }.getOrNull()
            main.post { onFailure(stored) }
        }
    }

    /** bannerlator-game-env applies "defaults" only where the inherited environment has no value. */
    @Synchronized
    fun publish(context: Context, config: GameEnvironment.Config = read(context)) {
        val defaults = JSONObject(GameEnvironment.defaults(SessionPrefs.fexPreset(context)))
        write(File(LinuxRuntime.rootDir(context), GUEST_FILE), encode(config).put("defaults", defaults).toString())
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
