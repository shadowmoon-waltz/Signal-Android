package org.thoughtcrime.securesms.components.settings.app.fork

data class ForkSettingsState(
  val hideInsights: Boolean,
  val showReactionTimestamps: Boolean,
  val forceWebsocketMode: Boolean,
  val fastCustomReactionChange: Boolean,
  val copyTextOpensPopup: Boolean,
  val conversationDeleteInMenu: Boolean,
  val swipeToRightAction: String,
  val rangeMultiSelect: Boolean,
  val longPressMultiSelect: Boolean,
  val alsoShowProfileName: Boolean,
  val manageGroupTweaks: Boolean,
  val swipeToLeftAction: String,
  val trashNoPromptForMe: Boolean,
  val promptMp4AsGif: Boolean,
  val altCollapseMediaKeyboard: Boolean
)
