package com.firestream.chat.domain.usecase.contact

import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ContactRepository
import javax.inject.Inject

class SyncContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository
) {
    suspend operator fun invoke(): Result<List<Contact>> {
        return contactRepository.syncContacts()
    }
}
