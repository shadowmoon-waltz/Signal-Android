package org.thoughtcrime.securesms.components.settings.app.fork

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.livedata.Store

class ForkSettingsViewModel : ViewModel() {
  private val store: Store<ForkSettingsState>

  init {
    val initialState = ForkSettingsState(
      hideInsights = SignalStore.settings().isHideInsights,
      showReactionTimestamps = SignalStore.settings().isShowReactionTimestamps,
      forceWebsocketMode = SignalStore.settings().isForceWebsocketMode,
      fastCustomReactionChange = SignalStore.settings().isFastCustomReactionChange,
      copyTextOpensPopup = SignalStore.settings().isCopyTextOpensPopup,
      conversationDeleteInMenu = SignalStore.settings().isConversationDeleteInMenu
    )

    store = Store(initialState)
  }

  val state: LiveData<ForkSettingsState> = store.stateLiveData

  fun setHideInsights(hideInsights: Boolean) {
    store.update { it.copy(hideInsights = hideInsights) }
    SignalStore.settings().isHideInsights = hideInsights
  }

  fun setShowReactionTimestamps(showReactionTimestamps: Boolean) {
    store.update { it.copy(showReactionTimestamps = showReactionTimestamps) }
    SignalStore.settings().isShowReactionTimestamps = showReactionTimestamps
  }

  fun setForceWebsocketMode(forceWebsocketMode: Boolean) {
    store.update { it.copy(forceWebsocketMode = forceWebsocketMode) }
    SignalStore.settings().isForceWebsocketMode = forceWebsocketMode
  }

  fun setFastCustomReactionChange(fastCustomReactionChange: Boolean) {
    store.update { it.copy(fastCustomReactionChange = fastCustomReactionChange) }
    SignalStore.settings().isFastCustomReactionChange = fastCustomReactionChange
  }

  fun setCopyTextOpensPopup(copyTextOpensPopup: Boolean) {
    store.update { it.copy(copyTextOpensPopup = copyTextOpensPopup) }
    SignalStore.settings().isCopyTextOpensPopup = copyTextOpensPopup
  }

  fun setConversationDeleteInMenu(conversationDeleteInMenu: Boolean) {
    store.update { it.copy(conversationDeleteInMenu = conversationDeleteInMenu) }
    SignalStore.settings().isConversationDeleteInMenu = conversationDeleteInMenu
  }
}
