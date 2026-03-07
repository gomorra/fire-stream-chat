package com.firestream.chat.domain.usecase.contact

import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ContactRepository
import javax.inject.Inject

class SearchContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository
) {
    suspend operator fun invoke(query: String): List<Contact> {
        return contactRepository.searchContacts(query)
    }
}
