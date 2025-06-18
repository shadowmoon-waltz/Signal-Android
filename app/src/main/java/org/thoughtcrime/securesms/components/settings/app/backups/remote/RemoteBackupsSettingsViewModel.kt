/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.backups.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.signal.core.util.billing.BillingPurchaseResult
import org.signal.core.util.bytes
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.signal.core.util.throttleLatest
import org.signal.donations.InAppPaymentType
import org.thoughtcrime.securesms.backup.ArchiveUploadProgress
import org.thoughtcrime.securesms.backup.DeletionState
import org.thoughtcrime.securesms.backup.v2.BackupFrequency
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.backup.v2.ui.status.BackupStatusData
import org.thoughtcrime.securesms.backup.v2.ui.subscription.MessageBackupsType
import org.thoughtcrime.securesms.banner.banners.MediaRestoreProgressBanner
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatMoney
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.RecurringInAppPaymentRepository
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.attachmentUpdates
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.BackupMessagesJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.protos.ArchiveUploadProgressState
import org.thoughtcrime.securesms.service.MessageBackupListener
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel for state management of RemoteBackupsSettingsFragment
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBackupsSettingsViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(RemoteBackupsSettingsViewModel::class)
  }

  private val _state = MutableStateFlow(
    RemoteBackupsSettingsState(
      backupsEnabled = SignalStore.backup.areBackupsEnabled,
      canViewBackupKey = !TextSecurePreferences.isUnauthorizedReceived(AppDependencies.application),
      lastBackupTimestamp = SignalStore.backup.lastBackupTime,
      backupsFrequency = SignalStore.backup.backupFrequency,
      canBackUpUsingCellular = SignalStore.backup.backupWithCellular,
      canRestoreUsingCellular = SignalStore.backup.restoreWithCellular
    )
  )

  private val _restoreState: MutableStateFlow<BackupRestoreState> = MutableStateFlow(BackupRestoreState.None)
  private val latestPurchaseId = MutableSharedFlow<InAppPaymentTable.InAppPaymentId>()

  val state: StateFlow<RemoteBackupsSettingsState> = _state
  val restoreState: StateFlow<BackupRestoreState> = _restoreState

  init {
    viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(backupMediaSize = SignalDatabase.attachments.getEstimatedArchiveMediaSize()) }
    }

    viewModelScope.launch(Dispatchers.IO) {
      SignalStore.backup.deletionStateFlow.collectLatest {
        refresh()
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      latestPurchaseId
        .flatMapLatest { id -> InAppPaymentsRepository.observeUpdates(id).asFlow() }
        .collectLatest { purchase ->
          refreshState(purchase)
        }
    }

    viewModelScope.launch(Dispatchers.IO) {
      AppDependencies
        .databaseObserver
        .attachmentUpdates()
        .throttleLatest(5.seconds)
        .collectLatest {
          _state.update { it.copy(backupMediaSize = SignalDatabase.attachments.getEstimatedArchiveMediaSize()) }
        }
    }

    viewModelScope.launch(Dispatchers.IO) {
      val restoreProgress = MediaRestoreProgressBanner()

      var optimizedRemainingBytes = 0L
      while (isActive) {
        if (restoreProgress.enabled) {
          Log.d(TAG, "Backup is being restored. Collecting updates.")
          restoreProgress
            .dataFlow
            .takeWhile { it !is BackupStatusData.RestoringMedia || it.restoreStatus != BackupStatusData.RestoreStatus.FINISHED }
            .collectLatest { latest ->
              _restoreState.update { BackupRestoreState.FromBackupStatusData(latest) }
            }
        } else if (
          !SignalStore.backup.optimizeStorage &&
          SignalStore.backup.userManuallySkippedMediaRestore &&
          SignalDatabase.attachments.getOptimizedMediaAttachmentSize().also { optimizedRemainingBytes = it } > 0
        ) {
          _restoreState.update { BackupRestoreState.Ready(optimizedRemainingBytes.bytes.toUnitString()) }
        } else if (SignalStore.backup.totalRestorableAttachmentSize > 0L) {
          _restoreState.update { BackupRestoreState.Ready(SignalStore.backup.totalRestorableAttachmentSize.bytes.toUnitString()) }
        } else if (BackupRepository.shouldDisplayBackupFailedSettingsRow()) {
          _restoreState.update { BackupRestoreState.FromBackupStatusData(BackupStatusData.BackupFailed) }
        } else if (BackupRepository.shouldDisplayCouldNotCompleteBackupSettingsRow()) {
          _restoreState.update { BackupRestoreState.FromBackupStatusData(BackupStatusData.CouldNotCompleteBackup) }
        } else {
          _restoreState.update { BackupRestoreState.None }
        }

        delay(1.seconds)
      }
    }

    viewModelScope.launch {
      var previous: ArchiveUploadProgressState.State? = null
      ArchiveUploadProgress.progress
        .collect { current ->
          if (previous != null && current.state == ArchiveUploadProgressState.State.None) {
            _state.update {
              it.copy(lastBackupTimestamp = SignalStore.backup.lastBackupTime)
            }
            refreshState(null)
          }
          previous = current.state
        }
    }
  }

  fun setCanBackUpUsingCellular(canBackUpUsingCellular: Boolean) {
    SignalStore.backup.backupWithCellular = canBackUpUsingCellular
    _state.update { it.copy(canBackUpUsingCellular = canBackUpUsingCellular) }
  }

  fun setCanRestoreUsingCellular() {
    SignalStore.backup.restoreWithCellular = true
    _state.update { it.copy(canRestoreUsingCellular = true) }
  }

  fun setBackupsFrequency(backupsFrequency: BackupFrequency) {
    SignalStore.backup.backupFrequency = backupsFrequency
    _state.update { it.copy(backupsFrequency = backupsFrequency) }
    MessageBackupListener.setNextBackupTimeToIntervalFromNow()
    MessageBackupListener.schedule(AppDependencies.application)
  }

  fun beginMediaRestore() {
    BackupRepository.resumeMediaRestore()
  }

  fun skipMediaRestore() {
    BackupRepository.skipMediaRestore()

    if (SignalStore.backup.deletionState == DeletionState.AWAITING_MEDIA_DOWNLOAD) {
      BackupRepository.continueTurningOffAndDisablingBackups()
    }
  }

  fun requestDialog(dialog: RemoteBackupsSettingsState.Dialog) {
    _state.update { it.copy(dialog = dialog) }
  }

  fun requestSnackbar(snackbar: RemoteBackupsSettingsState.Snackbar) {
    _state.update { it.copy(snackbar = snackbar) }
  }

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      val id = SignalDatabase.inAppPayments.getLatestInAppPaymentByType(InAppPaymentType.RECURRING_BACKUP)?.id

      if (id != null) {
        latestPurchaseId.emit(id)
      } else {
        refreshState(null)
      }
    }
  }

  fun turnOffAndDeleteBackups() {
    requestDialog(RemoteBackupsSettingsState.Dialog.PROGRESS_SPINNER)

    viewModelScope.launch(Dispatchers.IO) {
      BackupRepository.turnOffAndDisableBackups()
    }
  }

  fun onBackupNowClick() {
    BackupMessagesJob.enqueue()
  }

  fun cancelUpload() {
    ArchiveUploadProgress.cancel()
  }

  private suspend fun refreshState(lastPurchase: InAppPaymentTable.InAppPayment?) {
    try {
      Log.i(TAG, "Performing a state refresh.")
      performStateRefresh(lastPurchase)
    } catch (e: Exception) {
      Log.w(TAG, "State refresh failed", e)
      throw e
    }
  }

  private suspend fun performStateRefresh(lastPurchase: InAppPaymentTable.InAppPayment?) {
    val tier = SignalStore.backup.latestBackupTier

    _state.update {
      it.copy(
        backupsEnabled = SignalStore.backup.areBackupsEnabled,
        backupState = RemoteBackupsSettingsState.BackupState.Loading,
        lastBackupTimestamp = SignalStore.backup.lastBackupTime,
        backupMediaSize = SignalDatabase.attachments.getEstimatedArchiveMediaSize(),
        backupsFrequency = SignalStore.backup.backupFrequency,
        canBackUpUsingCellular = SignalStore.backup.backupWithCellular,
        canRestoreUsingCellular = SignalStore.backup.restoreWithCellular,
        isOutOfStorageSpace = BackupRepository.shouldDisplayOutOfStorageSpaceUx()
      )
    }

    if (BackupRepository.shouldDisplayOutOfStorageSpaceUx()) {
      val paidType = BackupRepository.getBackupsType(MessageBackupTier.PAID) as? MessageBackupsType.Paid
      if (paidType != null) {
        _state.update {
          it.copy(
            totalAllowedStorageSpace = paidType.storageAllowanceBytes.bytes.toUnitString()
          )
        }
      }
    }

    if (lastPurchase?.state == InAppPaymentTable.State.PENDING) {
      Log.d(TAG, "We have a pending subscription.")
      _state.update {
        it.copy(
          backupState = RemoteBackupsSettingsState.BackupState.Pending(
            price = lastPurchase.data.amount!!.toFiatMoney()
          )
        )
      }

      return
    }

    if (SignalStore.backup.subscriptionStateMismatchDetected) {
      Log.d(TAG, "[subscriptionStateMismatchDetected] A mismatch was detected.")

      val hasActiveGooglePlayBillingSubscription = when (val purchaseResult = AppDependencies.billingApi.queryPurchases()) {
        is BillingPurchaseResult.Success -> {
          Log.d(TAG, "[subscriptionStateMismatchDetected] Found a purchase: $purchaseResult")
          purchaseResult.isAcknowledged && purchaseResult.isWithinTheLastMonth() && purchaseResult.isAutoRenewing
        }
        else -> {
          Log.d(TAG, "[subscriptionStateMismatchDetected] No purchase found in Google Play Billing: $purchaseResult")
          false
        }
      } || SignalStore.backup.backupTierInternalOverride == MessageBackupTier.PAID

      Log.d(TAG, "[subscriptionStateMismatchDetected] hasActiveGooglePlayBillingSubscription: $hasActiveGooglePlayBillingSubscription")

      val activeSubscription = withContext(Dispatchers.IO) {
        RecurringInAppPaymentRepository.getActiveSubscriptionSync(InAppPaymentSubscriberRecord.Type.BACKUP).getOrNull()
      }

      val hasActiveSignalSubscription = activeSubscription?.isActive == true

      Log.d(TAG, "[subscriptionStateMismatchDetected] hasActiveSignalSubscription: $hasActiveSignalSubscription")

      when {
        hasActiveSignalSubscription && !hasActiveGooglePlayBillingSubscription -> {
          val type = buildPaidTypeFromSubscription(activeSubscription.activeSubscription)

          if (type == null) {
            Log.d(TAG, "[subscriptionMismatchDetected] failed to load backup configuration. Likely a network error.")
            _state.update {
              it.copy(
                backupState = RemoteBackupsSettingsState.BackupState.Error
              )
            }

            return
          }

          _state.update {
            it.copy(
              backupState = RemoteBackupsSettingsState.BackupState.SubscriptionMismatchMissingGooglePlay(
                messageBackupsType = type,
                renewalTime = activeSubscription.activeSubscription.endOfCurrentPeriod.seconds
              )
            )
          }

          return
        }

        hasActiveSignalSubscription && hasActiveGooglePlayBillingSubscription -> {
          Log.d(TAG, "Found active signal subscription and active google play subscription. Clearing mismatch.")
          SignalStore.backup.subscriptionStateMismatchDetected = false
        }

        !hasActiveSignalSubscription && !hasActiveGooglePlayBillingSubscription -> {
          Log.d(TAG, "Found inactive signal subscription and inactive google play subscription. Clearing mismatch.")
          SignalStore.backup.subscriptionStateMismatchDetected = false
        }

        else -> {
          Log.w(TAG, "Hit unexpected subscription mismatch state: signal:false, google:true")
          return
        }
      }
    }

    when (tier) {
      MessageBackupTier.PAID -> {
        Log.d(TAG, "Attempting to retrieve subscription details for active PAID backup.")

        val type = withContext(Dispatchers.IO) {
          BackupRepository.getBackupsType(tier) as? MessageBackupsType.Paid
        }

        Log.d(TAG, "Attempting to retrieve current subscription...")
        val activeSubscription = withContext(Dispatchers.IO) {
          RecurringInAppPaymentRepository.getActiveSubscriptionSync(InAppPaymentSubscriberRecord.Type.BACKUP)
        }

        if (activeSubscription.isSuccess) {
          Log.d(TAG, "Retrieved subscription details.")

          val subscription = activeSubscription.getOrThrow().activeSubscription
          if (subscription != null) {
            Log.d(TAG, "Subscription found. Updating UI state with subscription details. Status: ${subscription.status}")

            val subscriberType = type ?: buildPaidTypeFromSubscription(subscription)
            if (subscriberType == null) {
              Log.d(TAG, "Failed to create backup type. Possible network error.")
              _state.update {
                it.copy(backupState = RemoteBackupsSettingsState.BackupState.Error)
              }

              return
            }

            _state.update {
              it.copy(
                hasRedemptionError = lastPurchase?.data?.error?.data_ == "409",
                backupState = when {
                  subscription.isCanceled && subscription.isActive -> RemoteBackupsSettingsState.BackupState.Canceled(
                    messageBackupsType = subscriberType,
                    renewalTime = subscription.endOfCurrentPeriod.seconds
                  )

                  subscription.isActive -> RemoteBackupsSettingsState.BackupState.ActivePaid(
                    messageBackupsType = subscriberType,
                    price = FiatMoney.fromSignalNetworkAmount(subscription.amount, Currency.getInstance(subscription.currency)),
                    renewalTime = subscription.endOfCurrentPeriod.seconds
                  )

                  else -> RemoteBackupsSettingsState.BackupState.Inactive(
                    messageBackupsType = subscriberType,
                    renewalTime = subscription.endOfCurrentPeriod.seconds
                  )
                }
              )
            }
          } else {
            Log.d(TAG, "ActiveSubscription had null subscription object.")
            if (SignalStore.backup.areBackupsEnabled) {
              _state.update {
                it.copy(
                  backupState = RemoteBackupsSettingsState.BackupState.NotFound
                )
              }
            } else if (lastPurchase != null && lastPurchase.endOfPeriod > System.currentTimeMillis().milliseconds) {
              val canceledType = type ?: buildPaidTypeFromInAppPayment(lastPurchase)
              if (canceledType == null) {
                Log.w(TAG, "Failed to load canceled type information. Possible network error.")
                _state.update {
                  it.copy(
                    backupState = RemoteBackupsSettingsState.BackupState.Error
                  )
                }
              } else {
                _state.update {
                  it.copy(
                    backupState = RemoteBackupsSettingsState.BackupState.Canceled(
                      messageBackupsType = canceledType,
                      renewalTime = lastPurchase.endOfPeriod
                    )
                  )
                }
              }
            } else {
              val inactiveType = type ?: buildPaidTypeWithoutPricing()
              if (inactiveType == null) {
                Log.w(TAG, "Failed to load inactive type information. Possible network error.")
                _state.update {
                  it.copy(
                    backupState = RemoteBackupsSettingsState.BackupState.Error
                  )
                }
              } else {
                _state.update {
                  it.copy(
                    backupState = RemoteBackupsSettingsState.BackupState.Inactive(
                      messageBackupsType = inactiveType,
                      renewalTime = lastPurchase?.endOfPeriod ?: 0.seconds
                    )
                  )
                }
              }
            }
          }
        } else {
          Log.d(TAG, "Failed to load ActiveSubscription data. Updating UI state with error.")
          _state.update {
            it.copy(
              backupState = RemoteBackupsSettingsState.BackupState.Error
            )
          }
        }
      }

      MessageBackupTier.FREE -> {
        val type = withContext(Dispatchers.IO) {
          BackupRepository.getBackupsType(tier) as MessageBackupsType.Free
        }

        val backupState = if (SignalStore.backup.areBackupsEnabled) {
          RemoteBackupsSettingsState.BackupState.ActiveFree(type)
        } else {
          RemoteBackupsSettingsState.BackupState.Inactive(type)
        }

        Log.d(TAG, "Updating UI state with $backupState FREE tier.")
        _state.update { it.copy(backupState = backupState) }
      }

      null -> {
        Log.d(TAG, "Updating UI state with NONE null tier.")
        _state.update { it.copy(backupState = RemoteBackupsSettingsState.BackupState.None) }
      }
    }
  }

  /**
   * Builds out a Paid type utilizing pricing information stored in the user's active subscription object.
   *
   * @return A paid type, or null if we were unable to get the backup level configuration.
   */
  private fun buildPaidTypeFromSubscription(subscription: ActiveSubscription.Subscription): MessageBackupsType.Paid? {
    val config = BackupRepository.getBackupLevelConfiguration() ?: return null

    val price = FiatMoney.fromSignalNetworkAmount(subscription.amount, Currency.getInstance(subscription.currency))
    return MessageBackupsType.Paid(
      pricePerMonth = price,
      storageAllowanceBytes = config.storageAllowanceBytes,
      mediaTtl = config.mediaTtlDays.days
    )
  }

  /**
   * Builds out a Paid type utilizing pricing information stored in the given in-app payment.
   *
   * @return A paid type, or null if we were unable to get the backup level configuration.
   */
  private fun buildPaidTypeFromInAppPayment(inAppPayment: InAppPaymentTable.InAppPayment): MessageBackupsType.Paid? {
    val config = BackupRepository.getBackupLevelConfiguration() ?: return null

    val price = inAppPayment.data.amount!!.toFiatMoney()
    return MessageBackupsType.Paid(
      pricePerMonth = price,
      storageAllowanceBytes = config.storageAllowanceBytes,
      mediaTtl = config.mediaTtlDays.days
    )
  }

  /**
   * In the case of an Inactive subscription, we only care about the storage allowance and TTL, both of which we can
   * grab from the backup level configuration.
   *
   * @return A paid type, or null if we were unable to get the backup level configuration.
   */
  private fun buildPaidTypeWithoutPricing(): MessageBackupsType? {
    val config = BackupRepository.getBackupLevelConfiguration() ?: return null

    return MessageBackupsType.Paid(
      pricePerMonth = FiatMoney(BigDecimal.ZERO, Currency.getInstance(Locale.getDefault())),
      storageAllowanceBytes = config.storageAllowanceBytes,
      mediaTtl = config.mediaTtlDays.days
    )
  }
}
