package com.firestream.chat.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Reminder
import com.firestream.chat.domain.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduledRemindersUiState(
    val reminders: List<Reminder> = emptyList(),
    val isLoading: Boolean = true,
    val error: AppError? = null
)

/**
 * Pass-through ViewModel for the "Scheduled reminders" overview screen — mirrors
 * [com.firestream.chat.ui.starred.StarredMessagesViewModel]. [ReminderDao.observeAll]
 * already orders by `fireAtMs ASC`, so the emitted list is soonest-first without any
 * extra sort here.
 */
@HiltViewModel
class ScheduledRemindersViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduledRemindersUiState())
    val uiState: StateFlow<ScheduledRemindersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            reminderRepository.observePending()
                .catch { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e), isLoading = false) }
                .collect { reminders ->
                    _uiState.value = _uiState.value.copy(reminders = reminders, isLoading = false)
                }
        }
    }

    fun cancel(messageId: String) {
        viewModelScope.launch {
            reminderRepository.cancel(messageId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }
}
