package com.seema.assistant

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Long-term memory tools on top of [SeemaMemory]. KSP generates `generatedTools()`. */
class SeemaMemoryTools {

    @Tool(
        description = "Saves a fact to long-term memory so it is remembered across restarts. Write it " +
            "as one short, self-contained sentence about the user, e.g. 'The user's car is parked at B-12'."
    )
    fun rememberFact(
        @Param("The fact to remember, as one self-contained sentence") fact: String
    ): Map<String, String> {
        if (fact.isBlank()) return failure("Nothing to remember.")
        val entry = SeemaMemory.add(fact)
        return mapOf("status" to "ok", "remembered" to entry.text, "total_memories" to SeemaMemory.all().size.toString())
    }

    @Tool(
        description = "Searches long-term memory. Omit the query to list everything remembered, " +
            "newest first."
    )
    fun recallMemories(
        @Param("Optional keywords, e.g. 'car parking' or 'birthday'") query: String? = null
    ): Map<String, String> {
        val found = if (query.isNullOrBlank()) SeemaMemory.all().reversed() else SeemaMemory.search(query)
        if (found.isEmpty()) return mapOf("status" to "ok", "count" to "0", "memories" to "nothing found")
        return mapOf(
            "status" to "ok",
            "count" to found.size.toString(),
            "memories" to found.take(MAX_RESULTS).joinToString("; ") { "${it.text} (saved ${describe(it.savedAt)})" }
        )
    }

    @Tool(
        description = "Deletes remembered facts that match the description. If several match, " +
            "returns them so the user can choose; then call again with the exact fact text."
    )
    fun forgetMemory(
        @Param("Words from the fact to forget, or its exact text") description: String
    ): Map<String, String> {
        val wanted = SeemaMemory.normalize(description)
        if (wanted.isEmpty()) return failure("Say what to forget.")
        val exact = SeemaMemory.all().filter { SeemaMemory.normalize(it.text) == wanted }
        val matches = exact.ifEmpty { SeemaMemory.search(description) }
        return when {
            matches.isEmpty() -> failure("No memory matches '$description'.")
            matches.size > 1 -> failure(
                "Several memories match: ${matches.take(MAX_RESULTS).joinToString("; ") { it.text }}. Ask which one."
            )
            else -> {
                SeemaMemory.remove(setOf(matches.single().id))
                mapOf("status" to "ok", "forgotten" to matches.single().text)
            }
        }
    }

    @Tool(description = "Deletes ALL remembered facts. Only call after the user explicitly confirmed.")
    fun forgetEverything(): Map<String, String> =
        mapOf("status" to "ok", "forgotten_count" to SeemaMemory.clear().toString())

    private fun describe(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)

    private fun failure(message: String): Map<String, String> = mapOf("status" to "error", "error" to message)

    private companion object {
        const val MAX_RESULTS = 15
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
