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
      conversationDeleteInMenu = SignalStore.settings().isConversationDeleteInMenu,
      SignalStore.settings().swipeToRightAction,
      rangeMultiSelect = SignalStore.settings().isRangeMultiSelect,
      longPressMultiSelect = SignalStore.settings().isLongPressMultiSelect,
      alsoShowProfileName = SignalStore.settings().isAlsoShowProfileName,
      manageGroupTweaks = SignalStore.settings().isManageGroupTweaks,
      SignalStore.settings().swipeToLeftAction,
      trashNoPromptForMe = SignalStore.settings().isTrashNoPromptForMe,
      promptMp4AsGif = SignalStore.settings().isPromptMp4AsGif,
      altCollapseMediaKeyboard = SignalStore.settings().isAltCollapseMediaKeyboard,
      altCloseMediaSelection = SignalStore.settings().isAltCloseMediaSelection
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

  fun setSwipeToRightAction(swipeToRightAction: String) {
    store.update { it.copy(swipeToRightAction = swipeToRightAction) }
    SignalStore.settings().swipeToRightAction = swipeToRightAction
  }

  fun setRangeMultiSelect(rangeMultiSelect: Boolean) {
    store.update { it.copy(rangeMultiSelect = rangeMultiSelect) }
    SignalStore.settings().isRangeMultiSelect = rangeMultiSelect
  }

  fun setLongPressMultiSelect(longPressMultiSelect: Boolean) {
    store.update { it.copy(longPressMultiSelect = longPressMultiSelect) }
    SignalStore.settings().isLongPressMultiSelect = longPressMultiSelect
  }

  fun setAlsoShowProfileName(alsoShowProfileName: Boolean) {
    store.update { it.copy(alsoShowProfileName = alsoShowProfileName) }
    SignalStore.settings().isAlsoShowProfileName = alsoShowProfileName
  }

  fun setManageGroupTweaks(manageGroupTweaks: Boolean) {
    store.update { it.copy(manageGroupTweaks = manageGroupTweaks) }
    SignalStore.settings().isAlsoShowProfileName = manageGroupTweaks
  }

  fun setSwipeToLeftAction(swipeToLeftAction: String) {
    store.update { it.copy(swipeToLeftAction = swipeToLeftAction) }
    SignalStore.settings().swipeToLeftAction = swipeToLeftAction
  }

  fun setTrashNoPromptForMe(trashNoPromptForMe: Boolean) {
    store.update { it.copy(trashNoPromptForMe = trashNoPromptForMe) }
    SignalStore.settings().isTrashNoPromptForMe = trashNoPromptForMe
  }

  fun setPromptMp4AsGif(promptMp4AsGif: Boolean) {
    store.update { it.copy(promptMp4AsGif = promptMp4AsGif) }
    SignalStore.settings().isPromptMp4AsGif = promptMp4AsGif
  }

  fun setAltCollapseMediaKeyboard(altCollapseMediaKeyboard: Boolean) {
    store.update { it.copy(altCollapseMediaKeyboard = altCollapseMediaKeyboard) }
    SignalStore.settings().isAltCollapseMediaKeyboard = altCollapseMediaKeyboard
  }

  fun setAltCloseMediaSelection(altCloseMediaSelection: Boolean) {
    store.update { it.copy(altCloseMediaSelection = altCloseMediaSelection) }
    SignalStore.settings().isAltCloseMediaSelection = altCloseMediaSelection
  }
}
