package io.github.sds100.keymapper.promode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sds100.keymapper.util.ui.NavigationViewModel
import io.github.sds100.keymapper.util.ui.NavigationViewModelImpl
import io.github.sds100.keymapper.util.ui.PopupViewModel
import io.github.sds100.keymapper.util.ui.PopupViewModelImpl
import io.github.sds100.keymapper.util.ui.ResourceProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class ProModeViewModel(
    resourceProvider: ResourceProvider,
    private val useCase: ProModeSetupUseCase,
) : ViewModel(),
    ResourceProvider by resourceProvider,
    PopupViewModel by PopupViewModelImpl(),
    NavigationViewModel by NavigationViewModelImpl() {

    companion object {
        private const val WARNING_COUNT_DOWN_DURATION = 5
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val proModeWarningState: StateFlow<ProModeWarningState> =
        useCase.isWarningUnderstood.flatMapLatest { isUnderstood ->
            if (isUnderstood) {
                flowOf(ProModeWarningState.Understood)
            } else {
                flow<ProModeWarningState> {
                    repeat(WARNING_COUNT_DOWN_DURATION) {
                        emit(ProModeWarningState.CountingDown(WARNING_COUNT_DOWN_DURATION - it))
                        delay(1000L)
                    }

                    emit(ProModeWarningState.Idle)
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ProModeWarningState.CountingDown(
                WARNING_COUNT_DOWN_DURATION,
            ),
        )

    fun onWarningButtonClick() {
        useCase.onUnderstoodWarning()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val resourceProvider: ResourceProvider,
        private val useCase: ProModeSetupUseCase,
    ) : ViewModelProvider.NewInstanceFactory() {

        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProModeViewModel(resourceProvider, useCase) as T
    }
}

sealed class ProModeWarningState {
    data class CountingDown(val seconds: Int) : ProModeWarningState()
    data object Idle : ProModeWarningState()
    data object Understood : ProModeWarningState()
}

data class ProModeSetupState(
    val isRootDetected: Boolean,
    val isShizukuDetected: Boolean,

)
