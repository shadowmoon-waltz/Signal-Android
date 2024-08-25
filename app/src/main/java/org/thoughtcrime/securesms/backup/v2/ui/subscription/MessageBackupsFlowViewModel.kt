/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.ui.subscription

import android.text.TextUtils
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.money.FiatMoney
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatValue
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppDonations
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository.toPaymentSourceType
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.gateway.GatewayOrderStrategy
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.lock.v2.PinKeyboardType
import org.thoughtcrime.securesms.lock.v2.SvrConstants
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.signalservice.api.kbs.PinHashUtil.verifyLocalPinHash
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration
import java.math.BigDecimal

class MessageBackupsFlowViewModel : ViewModel() {
  private val internalStateFlow = MutableStateFlow(
    MessageBackupsFlowState(
      availableBackupTypes = emptyList(),
      selectedMessageBackupTier = SignalStore.backup.backupTier,
      availablePaymentMethods = GatewayOrderStrategy.getStrategy().orderedGateways.filter { InAppDonations.isPaymentSourceAvailable(it.toPaymentSourceType(), InAppPaymentType.RECURRING_BACKUP) },
      startScreen = if (SignalStore.backup.backupTier == null) MessageBackupsScreen.EDUCATION else MessageBackupsScreen.TYPE_SELECTION
    )
  )

  private val internalPinState = mutableStateOf("")
  private var isDowngrading = false

  val stateFlow: StateFlow<MessageBackupsFlowState> = internalStateFlow
  val pinState: State<String> = internalPinState

  init {
    viewModelScope.launch {
      internalStateFlow.update {
        it.copy(
          availableBackupTypes = BackupRepository.getAvailableBackupsTypes(
            if (!RemoteConfig.messageBackups) emptyList() else listOf(MessageBackupTier.FREE, MessageBackupTier.PAID)
          )
        )
      }
    }
  }

  fun goToNextScreen() {
    val pinSnapshot = pinState.value

    internalStateFlow.update {
      when (it.screen) {
        MessageBackupsScreen.EDUCATION -> it.copy(screen = MessageBackupsScreen.PIN_EDUCATION)
        MessageBackupsScreen.PIN_EDUCATION -> it.copy(screen = MessageBackupsScreen.PIN_CONFIRMATION)
        MessageBackupsScreen.PIN_CONFIRMATION -> validatePinAndUpdateState(it, pinSnapshot)
        MessageBackupsScreen.TYPE_SELECTION -> validateTypeAndUpdateState(it)
        MessageBackupsScreen.CHECKOUT_SHEET -> validateGatewayAndUpdateState(it)
        MessageBackupsScreen.CREATING_IN_APP_PAYMENT -> error("This is driven by an async coroutine.")
        MessageBackupsScreen.CANCELLATION_DIALOG -> it.copy(screen = MessageBackupsScreen.PROCESS_CANCELLATION)
        MessageBackupsScreen.PROCESS_PAYMENT -> it.copy(screen = MessageBackupsScreen.COMPLETED)
        MessageBackupsScreen.PROCESS_CANCELLATION -> it.copy(screen = MessageBackupsScreen.COMPLETED)
        MessageBackupsScreen.PROCESS_FREE -> it.copy(screen = MessageBackupsScreen.COMPLETED)
        MessageBackupsScreen.COMPLETED -> error("Unsupported state transition from terminal state COMPLETED")
      }
    }
  }

  fun goToPreviousScreen() {
    internalStateFlow.update {
      if (it.screen == it.startScreen) {
        it.copy(screen = MessageBackupsScreen.COMPLETED)
      } else {
        val previousScreen = when (it.screen) {
          MessageBackupsScreen.EDUCATION -> MessageBackupsScreen.COMPLETED
          MessageBackupsScreen.PIN_EDUCATION -> MessageBackupsScreen.EDUCATION
          MessageBackupsScreen.PIN_CONFIRMATION -> MessageBackupsScreen.PIN_EDUCATION
          MessageBackupsScreen.TYPE_SELECTION -> MessageBackupsScreen.PIN_CONFIRMATION
          MessageBackupsScreen.CHECKOUT_SHEET -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.CREATING_IN_APP_PAYMENT -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.PROCESS_PAYMENT -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.PROCESS_CANCELLATION -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.PROCESS_FREE -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.CANCELLATION_DIALOG -> MessageBackupsScreen.TYPE_SELECTION
          MessageBackupsScreen.COMPLETED -> error("Unsupported state transition from terminal state COMPLETED")
        }

        it.copy(screen = previousScreen)
      }
    }
  }

  fun displayCancellationDialog() {
    internalStateFlow.update {
      check(it.screen == MessageBackupsScreen.TYPE_SELECTION)
      it.copy(screen = MessageBackupsScreen.CANCELLATION_DIALOG)
    }
  }

  fun onPinEntryUpdated(pin: String) {
    internalPinState.value = pin
  }

  fun onPinKeyboardTypeUpdated(pinKeyboardType: PinKeyboardType) {
    internalStateFlow.update { it.copy(pinKeyboardType = pinKeyboardType) }
  }

  fun onPaymentMethodUpdated(paymentMethod: InAppPaymentData.PaymentMethodType) {
    internalStateFlow.update { it.copy(selectedPaymentMethod = paymentMethod) }
  }

  fun onMessageBackupTierUpdated(messageBackupTier: MessageBackupTier, messageBackupTierLabel: String) {
    internalStateFlow.update {
      it.copy(
        selectedMessageBackupTier = messageBackupTier,
        selectedMessageBackupTierLabel = messageBackupTierLabel
      )
    }
  }

  fun onCancellationComplete() {
    if (isDowngrading) {
      SignalStore.backup.areBackupsEnabled = true
      SignalStore.backup.backupTier = MessageBackupTier.FREE

      // TODO [message-backups] -- Trigger backup now?
    }
  }

  private fun validatePinAndUpdateState(state: MessageBackupsFlowState, pin: String): MessageBackupsFlowState {
    val pinHash = SignalStore.svr.localPinHash

    if (pinHash == null || TextUtils.isEmpty(pin) || pin.length < SvrConstants.MINIMUM_PIN_LENGTH) {
      return state.copy(
        screen = MessageBackupsScreen.PIN_CONFIRMATION,
        displayIncorrectPinError = true
      )
    }

    if (!verifyLocalPinHash(pinHash, pin)) {
      return state.copy(
        screen = MessageBackupsScreen.PIN_CONFIRMATION,
        displayIncorrectPinError = true
      )
    }

    internalPinState.value = ""
    return state.copy(
      screen = MessageBackupsScreen.TYPE_SELECTION,
      displayIncorrectPinError = false
    )
  }

  private fun validateTypeAndUpdateState(state: MessageBackupsFlowState): MessageBackupsFlowState {
    return when (state.selectedMessageBackupTier!!) {
      MessageBackupTier.FREE -> {
        if (SignalStore.backup.backupTier == MessageBackupTier.PAID) {
          isDowngrading = true
          state.copy(screen = MessageBackupsScreen.PROCESS_CANCELLATION)
        } else {
          SignalStore.backup.areBackupsEnabled = true
          SignalStore.backup.backupTier = MessageBackupTier.FREE

          state.copy(screen = MessageBackupsScreen.PROCESS_FREE)
        }
      }
      MessageBackupTier.PAID -> state.copy(screen = MessageBackupsScreen.CHECKOUT_SHEET)
    }
  }

  private fun validateGatewayAndUpdateState(state: MessageBackupsFlowState): MessageBackupsFlowState {
    val backupsType = state.availableBackupTypes.first { it.tier == state.selectedMessageBackupTier }

    viewModelScope.launch(Dispatchers.IO) {
      withContext(Dispatchers.Main) {
        internalStateFlow.update { it.copy(inAppPayment = null) }
      }

      val currency = SignalStore.inAppPayments.getSubscriptionCurrency(InAppPaymentSubscriberRecord.Type.BACKUP)

      SignalDatabase.inAppPayments.clearCreated()
      val id = SignalDatabase.inAppPayments.insert(
        type = InAppPaymentType.RECURRING_BACKUP,
        state = InAppPaymentTable.State.CREATED,
        subscriberId = null,
        endOfPeriod = null,
        inAppPaymentData = InAppPaymentData(
          badge = null,
          label = state.selectedMessageBackupTierLabel!!,
          amount = if (backupsType is MessageBackupsType.Paid) backupsType.pricePerMonth.toFiatValue() else FiatMoney(BigDecimal.ZERO, currency).toFiatValue(),
          level = SubscriptionsConfiguration.BACKUPS_LEVEL.toLong(),
          recipientId = Recipient.self().id.serialize(),
          paymentMethodType = state.selectedPaymentMethod!!,
          redemption = InAppPaymentData.RedemptionState(
            stage = InAppPaymentData.RedemptionState.Stage.INIT
          )
        )
      )

      val inAppPayment = SignalDatabase.inAppPayments.getById(id)!!

      withContext(Dispatchers.Main) {
        internalStateFlow.update { it.copy(inAppPayment = inAppPayment, screen = MessageBackupsScreen.PROCESS_PAYMENT) }
      }
    }

    return state.copy(screen = MessageBackupsScreen.CREATING_IN_APP_PAYMENT)
  }
}
