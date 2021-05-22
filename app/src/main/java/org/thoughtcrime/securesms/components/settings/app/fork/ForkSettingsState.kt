package org.thoughtcrime.securesms.components.settings.app.fork

data class ForkSettingsState(
  val hideInsights: Boolean,
  val showReactionTimestamps: Boolean,
  val forceWebsocketMode: Boolean,
  val fastCustomReactionChange: Boolean
)
