package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun getContacts(): Flow<List<Contact>>
    suspend fun syncContacts(): Result<List<Contact>>
    suspend fun searchContacts(query: String): List<Contact>
}
