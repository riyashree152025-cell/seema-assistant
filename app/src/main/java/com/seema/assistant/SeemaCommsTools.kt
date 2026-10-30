package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Calling and texting tools. KSP generates `SeemaCommsTools.generatedTools()`. */
class SeemaCommsTools(context: Context) {

    private val appContext = context.applicationContext

    // -------------------------------------------------------------- contacts

    @Tool(
        description = "Looks up phone numbers in the user's contacts by name. " +
            "Always call this before makePhoneCall or sendSms when the user names a person."
    )
    fun findContacts(
        @Param("The person's name as the user said it, e.g. 'Mummy', 'John', 'Rahul Sharma'") name: String
    ): Map<String, String> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return failure("Contacts permission missing. Ask the user to allow Contacts for SEEMA.")
        }
        val query = name.trim()
        if (query.isEmpty()) return failure("No name given.")

        val matches = rankContacts(query).ifEmpty {
            relationAliases(query).flatMap { rankContacts(it) }.distinctBy { it.number }
        }
        if (matches.isEmpty()) return failure("No contact named '$query'.")

        val shown = matches.take(MAX_CONTACT_RESULTS)
        return mapOf(
            "status" to "ok",
            "match_count" to matches.size.toString(),
            "matches" to shown.mapIndexed { i, c -> "${i + 1}. ${c.name} (${c.type}): ${c.number}" }
                .joinToString("; ")
        )
    }

    // ----------------------------------------------------------------- calls

    @Tool(
        description = "Places a phone call immediately. Only call this after the user has " +
            "confirmed the exact contact name and number."
    )
    @SuppressLint("MissingPermission")
    fun makePhoneCall(
        @Param("Phone number from findContacts, or digits the user dictated") phoneNumber: String
    ): Map<String, String> {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return failure("Phone permission missing. Ask the user to allow Phone calls for SEEMA.")
        }
        val number = normalizeNumber(phoneNumber)
            ?: return failure("'$phoneNumber' is not a valid phone number.")

        return try {
            appContext.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
            // The call now owns the mic and speaker; stop follow-up listening.
            SeemaState.requestConversationEnd()
            mapOf("status" to "ok", "calling" to number)
        } catch (e: SecurityException) {
            failure("Android refused to place the call.")
        }
    }

    // ------------------------------------------------------------------- SMS

    @Tool(
        description = "Sends an SMS text message. Only call this after the user has confirmed " +
            "the recipient and the exact message text."
    )
    fun sendSms(
        @Param("Phone number from findContacts, or digits the user dictated") phoneNumber: String,
        @Param("The exact message text to send") message: String
    ): Map<String, String> {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return failure("SMS permission missing. Ask the user to allow SMS for SEEMA.")
        }
        val number = normalizeNumber(phoneNumber)
            ?: return failure("'$phoneNumber' is not a valid phone number.")
        val text = message.trim()
        if (text.isEmpty()) return failure("The message is empty.")

        val smsManager = smsManager()
        val parts = smsManager.divideMessage(text)
        val action = "${appContext.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val pendingParts = CountDownLatch(parts.size)
        val failedParts = AtomicInteger(0)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) failedParts.incrementAndGet()
                pendingParts.countDown()
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            val sentIntents = ArrayList(parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    appContext,
                    index,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
            })
            smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)

            // Tools run on Dispatchers.IO, so briefly blocking for the radio's answer is fine.
            val confirmed = pendingParts.await(SMS_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return when {
                failedParts.get() > 0 ->
                    failure("The network rejected the message. Check signal or SMS balance.")
                !confirmed -> mapOf("status" to "ok", "to" to number, "delivery" to "queued, not yet confirmed")
                else -> mapOf("status" to "ok", "to" to number, "delivery" to "sent")
            }
        } catch (e: Exception) {
            return failure("Could not send the SMS: ${e.message ?: e::class.java.simpleName}")
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }

    // ---------------------------------------------------------- reading SMS

    @Tool(
        description = "Reads the most recent SMS messages from the inbox, newest first, " +
            "optionally only those from one contact."
    )
    fun readMessages(
        @Param("How many messages to read, 1 to 5. Omit to read just the latest one.") count: Int? = null,
        @Param("Optional contact name, to read only messages from that person") fromContact: String? = null
    ): Map<String, String> {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return failure("SMS reading permission missing. Ask the user to allow SMS for SEEMA.")
        }
        val limit = (count ?: 1).coerceIn(1, MAX_MESSAGES)

        // When filtering by person, compare the last digits so +91 98... matches 098...
        val senderKeys = fromContact?.takeIf { it.isNotBlank() }?.let { name ->
            if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
                return failure("Contacts permission missing, so messages cannot be filtered by name.")
            }
            val contacts = rankContacts(name.trim()).ifEmpty {
                relationAliases(name.trim()).flatMap { rankContacts(it) }
            }
            if (contacts.isEmpty()) return failure("No contact named '$name'.")
            contacts.map { numberKey(it.number) }.toSet()
        }

        val messages = mutableListOf<String>()
        appContext.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            var scanned = 0
            while (messages.size < limit && scanned < MAX_SCANNED_MESSAGES && cursor.moveToNext()) {
                scanned++
                val address = cursor.getString(addressCol).orEmpty()
                if (senderKeys != null && numberKey(address) !in senderKeys) continue

                val sender = ContactNames.lookup(appContext, address) ?: spokenSender(address)
                val body = cursor.getString(bodyCol).orEmpty().trim().take(MAX_BODY_CHARS)
                val received = describeReceived(cursor.getLong(dateCol))
                messages += "${messages.size + 1}. From $sender, $received: $body"
            }
        }

        if (messages.isEmpty()) {
            return mapOf(
                "status" to "ok",
                "count" to "0",
                "messages" to if (fromContact.isNullOrBlank()) "The inbox is empty." else "No messages from $fromContact."
            )
        }
        return mapOf("status" to "ok", "count" to messages.size.toString(), "messages" to messages.joinToString("; "))
    }

    // --------------------------------------------------------------- helpers

    private fun numberKey(number: String): String =
        number.filter { it.isDigit() }.takeLast(SAME_NUMBER_DIGITS)

    /** Business senders like "VM-HDFCBK" stay as-is; unknown numbers are read by last digits. */
    private fun spokenSender(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= MIN_NUMBER_DIGITS && address.none { it.isLetter() }) {
            "an unknown number ending in ${digits.takeLast(4)}"
        } else {
            address.ifBlank { "an unknown sender" }
        }
    }

    private fun describeReceived(epochMillis: Long): String {
        val received = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val day = when (received.toLocalDate()) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> received.format(MESSAGE_DAY_FORMAT)
        }
        return "$day at ${received.format(MESSAGE_TIME_FORMAT)}"
    }

    private data class ContactPhone(val name: String, val number: String, val type: String)

    /** Contacts whose name matches [query], best match first, one entry per number. */
    private fun rankContacts(query: String): List<ContactPhone> {
        val wanted = query.lowercase(Locale.ROOT)
        val candidates = mutableListOf<ContactPhone>()

        appContext.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numberCol = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val typeCol = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val labelCol = cursor.getColumnIndexOrThrow(Phone.LABEL)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberCol) ?: continue
                val type = Phone.getTypeLabel(
                    appContext.resources, cursor.getInt(typeCol), cursor.getString(labelCol)
                ).toString().lowercase(Locale.ROOT)
                candidates += ContactPhone(cursor.getString(nameCol).orEmpty(), number, type)
            }
        }

        fun rank(contact: ContactPhone): Int {
            val name = contact.name.lowercase(Locale.ROOT)
            return when {
                name == wanted -> 0
                name.startsWith(wanted) -> 1
                name.split(' ').any { it == wanted } -> 2
                else -> 3
            }
        }
        return candidates
            .distinctBy { numberKey(it.number) }
            .sortedBy(::rank)
    }

    /** Other names people commonly save family contacts under. */
    private fun relationAliases(query: String): List<String> {
        val key = query.lowercase(Locale.ROOT)
        return RELATION_GROUPS.firstOrNull { key in it }?.filter { it != key }.orEmpty()
    }

    /** Keeps digits and a leading '+'; null if it is not a plausible phone number. */
    private fun normalizeNumber(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        val number = if (trimmed.startsWith("+")) "+$digits" else digits
        return number.takeIf { digits.length in MIN_NUMBER_DIGITS..MAX_NUMBER_DIGITS }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun failure(message: String): Map<String, String> =
        mapOf("status" to "error", "error" to message)

    private companion object {
        const val MAX_CONTACT_RESULTS = 5
        const val SAME_NUMBER_DIGITS = 10
        const val MIN_NUMBER_DIGITS = 3
        const val MAX_NUMBER_DIGITS = 15
        const val SMS_RESULT_TIMEOUT_SECONDS = 10L
        const val MAX_MESSAGES = 5
        const val MAX_SCANNED_MESSAGES = 500
        const val MAX_BODY_CHARS = 300

        val MESSAGE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val MESSAGE_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

        val RELATION_GROUPS = listOf(
            setOf("mummy", "mumma", "mom", "mother", "maa", "ma", "mama", "amma", "mommy"),
            setOf("papa", "pappa", "dad", "daddy", "father", "pitaji", "baba", "abbu"),
            setOf("bhaiya", "bhai", "brother", "bro"),
            setOf("didi", "sister", "sis"),
            setOf("dadi", "grandma", "grandmother", "nani"),
            setOf("dadaji", "dada", "grandpa", "grandfather", "nana")
        )
    }
}
