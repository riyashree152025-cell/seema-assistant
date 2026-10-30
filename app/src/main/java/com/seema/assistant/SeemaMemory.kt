package com.seema.assistant

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One remembered fact. */
data class MemoryEntry(val id: String, val text: String, val savedAt: Long)

/**
 * SEEMA's long-term memory: short facts saved as JSON in the app's private storage,
 * so they survive restarts and app updates. Call [init] once before use.
 */
object SeemaMemory {

    private const val TAG = "SeemaMemory"
    private const val FILE_NAME = "seema_memory.json"
    private const val MAX_ENTRIES = 300

    private lateinit var file: AtomicFile
    private val entries = mutableListOf<MemoryEntry>()

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
        entries += load()
        Log.i(TAG, "Loaded ${entries.size} memories")
    }

    @Synchronized
    fun all(): List<MemoryEntry> = entries.toList()

    /** Saves [text]; returns the existing entry instead if the same fact is already stored. */
    @Synchronized
    fun add(text: String): MemoryEntry {
        val clean = text.trim()
        entries.firstOrNull { normalize(it.text) == normalize(clean) }?.let { return it }
        val entry = MemoryEntry(UUID.randomUUID().toString(), clean, System.currentTimeMillis())
        entries += entry
        // Oldest facts make room once the cap is reached.
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        save()
        return entry
    }

    /** Entries containing every word (3+ letters) of [query], newest first. */
    @Synchronized
    fun search(query: String): List<MemoryEntry> {
        val words = normalize(query).split(' ').filter { it.length >= 3 }
        if (words.isEmpty()) return entries.reversed()
        return entries.filter { entry ->
            val text = normalize(entry.text)
            words.all { text.contains(it) }
        }.reversed()
    }

    @Synchronized
    fun remove(ids: Set<String>): Int {
        val before = entries.size
        entries.removeAll { it.id in ids }
        if (entries.size != before) save()
        return before - entries.size
    }

    @Synchronized
    fun clear(): Int {
        val count = entries.size
        entries.clear()
        save()
        return count
    }

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun load(): List<MemoryEntry> = try {
        val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            MemoryEntry(item.getString("id"), item.getString("text"), item.getLong("savedAt"))
        }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: JSONException) {
        Log.e(TAG, "Memory file is corrupt; starting empty", e)
        emptyList()
    }

    private fun save() {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id).put("text", entry.text).put("savedAt", entry.savedAt))
        }
        // AtomicFile writes to a temp file and swaps, so a crash mid-write never loses memories.
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.e(TAG, "Could not save memories", e)
        }
    }
}
