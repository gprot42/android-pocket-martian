package com.pocketmartian.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.pocketmartian.app.data.repository.ResponseHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: ResponseHistoryRepository
) : ViewModel() {
    val entries: StateFlow<List<com.pocketmartian.app.data.repository.ResponseHistoryEntry>> =
        historyRepository.entries

    fun clear() = historyRepository.clear()
}
