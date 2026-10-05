package com.pocketmartian.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.pocketmartian.app.data.repository.AppExitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    appExitRepository: AppExitRepository
) : ViewModel() {
    /** Read once: Android's record of the previous exit does not change while we run. */
    val lastExit: String? = appExitRepository.lastExit()
}
