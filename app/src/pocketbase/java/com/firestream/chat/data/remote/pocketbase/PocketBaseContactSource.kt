package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.ContactSource
import com.firestream.chat.domain.model.Contact
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Stays a stub through v0; contact-by-phone sync is out of scope. */
@Singleton
class PocketBaseContactSource @Inject constructor() : ContactSource {
    override suspend fun fetchAllRegisteredContacts(): List<Contact> = emptyList()
}
