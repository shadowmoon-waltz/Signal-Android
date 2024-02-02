/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.ui

import org.thoughtcrime.securesms.components.settings.app.subscription.donate.gateway.GatewayResponse
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.lock.v2.PinKeyboardType

data class MessageBackupsFlowState(
  val selectedMessageBackupsType: MessageBackupsType? = null,
  val availableBackupsTypes: List<MessageBackupsType> = emptyList(),
  val selectedPaymentGateway: GatewayResponse.Gateway? = null,
  val availablePaymentGateways: List<GatewayResponse.Gateway> = emptyList(),
  val pin: String = "",
  val pinKeyboardType: PinKeyboardType = SignalStore.pinValues().keyboardType
)
