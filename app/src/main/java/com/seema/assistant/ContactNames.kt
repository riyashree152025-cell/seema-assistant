package com.seema.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import androidx.core.content.ContextCompat

/** Reverse lookup: phone number → contact name. */
object ContactNames {

    /** Returns the saved contact name for [number], or null if unknown or Contacts is not allowed. */
    fun lookup(context: Context, number: String): String? {
        if (number.none { it.isDigit() }) return null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver
            .query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }
}
