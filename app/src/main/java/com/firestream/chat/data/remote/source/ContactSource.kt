package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.Contact

/**
 * Backend-neutral contacts boundary. Extracted in step 3c from the direct
 * `FirebaseFirestore` field in `ContactRepositoryImpl.syncContacts()`.
 *
 * The v0 surface is just the bulk fetch the repo needs for sync; phone-book
 * intersection still happens in the repo.
 */
interface ContactSource {
    /** Fetch all registered users known to the backend. */
    suspend fun fetchAllRegisteredContacts(): List<Contact>
}
