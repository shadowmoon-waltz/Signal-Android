/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.signal.core.util.billing.BillingPurchaseResult
import org.signal.core.util.logging.Log
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatValue
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.RecurringInAppPaymentRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.InAppPaymentError
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.InAppPaymentPurchaseTokenJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration
import kotlin.time.Duration.Companion.seconds

class MessageBackupsFlowViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(MessageBackupsFlowViewModel::class)
  }

  private val internalStateFlow = MutableStateFlow(
    MessageBackupsFlowState(
      availableBackupTypes = emptyList(),
      selectedMessageBackupTier = SignalStore.backup.backupTier,
      startScreen = if (SignalStore.backup.backupTier == null) MessageBackupsStage.EDUCATION else MessageBackupsStage.TYPE_SELECTION
    )
  )

  val stateFlow: StateFlow<MessageBackupsFlowState> = internalStateFlow

  init {
    check(SignalStore.backup.backupTier != MessageBackupTier.PAID) { "This screen does not support cancellation or downgrades." }

    viewModelScope.launch {
      try {
        ensureSubscriberIdForBackups()
        internalStateFlow.update {
          it.copy(
            hasBackupSubscriberAvailable = true
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to ensure a subscriber id exists.", e)
      }

      internalStateFlow.update {
        it.copy(
          availableBackupTypes = BackupRepository.getAvailableBackupsTypes(
            if (!RemoteConfig.messageBackups) emptyList() else listOf(MessageBackupTier.FREE, MessageBackupTier.PAID)
          )
        )
      }
    }

    viewModelScope.launch {
      AppDependencies.billingApi.getBillingPurchaseResults().collect { result ->
        when (result) {
          is BillingPurchaseResult.Success -> {
            internalStateFlow.update { it.copy(stage = MessageBackupsStage.PROCESS_PAYMENT) }

            try {
              handleSuccess(
                result,
                internalStateFlow.value.inAppPayment!!.id
              )

              internalStateFlow.update {
                it.copy(
                  stage = MessageBackupsStage.COMPLETED
                )
              }
            } catch (e: Exception) {
              internalStateFlow.update {
                it.copy(
                  stage = MessageBackupsStage.FAILURE,
                  failure = e
                )
              }
            }
          }

          else -> goToPreviousStage()
        }
      }
    }
  }

  /**
   * Go to the next stage of the pipeline, based off of the current stage and state data.
   */
  fun goToNextStage() {
    internalStateFlow.update {
      when (it.stage) {
        MessageBackupsStage.EDUCATION -> it.copy(stage = MessageBackupsStage.BACKUP_KEY_EDUCATION)
        MessageBackupsStage.BACKUP_KEY_EDUCATION -> it.copy(stage = MessageBackupsStage.BACKUP_KEY_RECORD)
        MessageBackupsStage.BACKUP_KEY_RECORD -> it.copy(stage = MessageBackupsStage.TYPE_SELECTION)
        MessageBackupsStage.TYPE_SELECTION -> validateTypeAndUpdateState(it)
        MessageBackupsStage.CHECKOUT_SHEET -> validateGatewayAndUpdateState(it)
        MessageBackupsStage.CREATING_IN_APP_PAYMENT -> error("This is driven by an async coroutine.")
        MessageBackupsStage.PROCESS_PAYMENT -> error("This is driven by an async coroutine.")
        MessageBackupsStage.PROCESS_FREE -> error("This is driven by an async coroutine.")
        MessageBackupsStage.COMPLETED -> error("Unsupported state transition from terminal state COMPLETED")
        MessageBackupsStage.FAILURE -> error("Unsupported state transition from terminal state FAILURE")
      }
    }
  }

  fun goToPreviousStage() {
    internalStateFlow.update {
      if (it.stage == it.startScreen) {
        it.copy(stage = MessageBackupsStage.COMPLETED)
      } else {
        val previousScreen = when (it.stage) {
          MessageBackupsStage.EDUCATION -> MessageBackupsStage.COMPLETED
          MessageBackupsStage.BACKUP_KEY_EDUCATION -> MessageBackupsStage.EDUCATION
          MessageBackupsStage.BACKUP_KEY_RECORD -> MessageBackupsStage.BACKUP_KEY_EDUCATION
          MessageBackupsStage.TYPE_SELECTION -> MessageBackupsStage.BACKUP_KEY_RECORD
          MessageBackupsStage.CHECKOUT_SHEET -> MessageBackupsStage.TYPE_SELECTION
          MessageBackupsStage.CREATING_IN_APP_PAYMENT -> MessageBackupsStage.CREATING_IN_APP_PAYMENT
          MessageBackupsStage.PROCESS_PAYMENT -> MessageBackupsStage.PROCESS_PAYMENT
          MessageBackupsStage.PROCESS_FREE -> MessageBackupsStage.PROCESS_FREE
          MessageBackupsStage.COMPLETED -> error("Unsupported state transition from terminal state COMPLETED")
          MessageBackupsStage.FAILURE -> error("Unsupported state transition from terminal state FAILURE")
        }

        it.copy(stage = previousScreen)
      }
    }
  }

  fun onMessageBackupTierUpdated(messageBackupTier: MessageBackupTier, messageBackupTierLabel: String) {
    internalStateFlow.update {
      it.copy(
        selectedMessageBackupTier = messageBackupTier,
        selectedMessageBackupTierLabel = messageBackupTierLabel
      )
    }
  }

  private fun validateTypeAndUpdateState(state: MessageBackupsFlowState): MessageBackupsFlowState {
    return when (state.selectedMessageBackupTier!!) {
      MessageBackupTier.FREE -> {
        SignalStore.backup.areBackupsEnabled = true
        SignalStore.backup.backupTier = MessageBackupTier.FREE

        state.copy(stage = MessageBackupsStage.COMPLETED)
      }

      MessageBackupTier.PAID -> state.copy(stage = MessageBackupsStage.CHECKOUT_SHEET)
    }
  }

  private fun validateGatewayAndUpdateState(state: MessageBackupsFlowState): MessageBackupsFlowState {
    check(state.selectedMessageBackupTier == MessageBackupTier.PAID)
    check(state.availableBackupTypes.any { it.tier == state.selectedMessageBackupTier })
    check(state.hasBackupSubscriberAvailable)

    viewModelScope.launch(Dispatchers.IO) {
      withContext(Dispatchers.Main) {
        internalStateFlow.update { it.copy(inAppPayment = null) }
      }

      val paidFiat = AppDependencies.billingApi.queryProduct()!!.price

      SignalDatabase.inAppPayments.clearCreated()
      val id = SignalDatabase.inAppPayments.insert(
        type = InAppPaymentType.RECURRING_BACKUP,
        state = InAppPaymentTable.State.CREATED,
        subscriberId = InAppPaymentsRepository.requireSubscriber(InAppPaymentSubscriberRecord.Type.BACKUP).subscriberId,
        endOfPeriod = null,
        inAppPaymentData = InAppPaymentData(
          badge = null,
          label = state.selectedMessageBackupTierLabel!!,
          amount = paidFiat.toFiatValue(),
          level = SubscriptionsConfiguration.BACKUPS_LEVEL.toLong(),
          recipientId = Recipient.self().id.serialize(),
          paymentMethodType = InAppPaymentData.PaymentMethodType.GOOGLE_PLAY_BILLING,
          redemption = InAppPaymentData.RedemptionState(
            stage = InAppPaymentData.RedemptionState.Stage.INIT
          )
        )
      )

      val inAppPayment = SignalDatabase.inAppPayments.getById(id)!!

      withContext(Dispatchers.Main) {
        internalStateFlow.update { it.copy(inAppPayment = inAppPayment, stage = MessageBackupsStage.PROCESS_PAYMENT) }
      }
    }

    return state.copy(stage = MessageBackupsStage.CREATING_IN_APP_PAYMENT)
  }

  /**
   * Ensures we have a SubscriberId created and available for use. This is considered safe because
   * the screen this is called in is assumed to only be accessible if the user does not currently have
   * a subscription.
   */
  private suspend fun ensureSubscriberIdForBackups() {
    val product = AppDependencies.billingApi.queryProduct() ?: error("No product available.")
    SignalStore.inAppPayments.setSubscriberCurrency(product.price.currency, InAppPaymentSubscriberRecord.Type.BACKUP)
    RecurringInAppPaymentRepository.ensureSubscriberId(InAppPaymentSubscriberRecord.Type.BACKUP).blockingAwait()
  }

  /**
   * Handles a successful BillingPurchaseResult. Updates the in app payment, enqueues the appropriate job chain,
   * and handles any resulting error. Like donations, we will wait up to 10s for the completion of the job chain.
   */
  @OptIn(FlowPreview::class)
  private suspend fun handleSuccess(result: BillingPurchaseResult.Success, inAppPaymentId: InAppPaymentTable.InAppPaymentId) {
    withContext(Dispatchers.IO) {
      val inAppPayment = SignalDatabase.inAppPayments.getById(inAppPaymentId)!!
      SignalDatabase.inAppPayments.update(
        inAppPayment.copy(
          data = inAppPayment.data.copy(
            redemption = inAppPayment.data.redemption!!.copy(
              googlePlayBillingPurchaseToken = result.purchaseToken
            )
          )
        )
      )

      InAppPaymentPurchaseTokenJob.createJobChain(inAppPayment).enqueue()
    }

    val terminalInAppPayment = withContext(Dispatchers.IO) {
      InAppPaymentsRepository.observeUpdates(inAppPaymentId).asFlow()
        .filter { it.state == InAppPaymentTable.State.END }
        .take(1)
        .timeout(10.seconds)
        .first()
    }

    if (terminalInAppPayment.data.error != null) {
      throw InAppPaymentError(terminalInAppPayment.data.error)
    } else {
      return
    }
  }
}
