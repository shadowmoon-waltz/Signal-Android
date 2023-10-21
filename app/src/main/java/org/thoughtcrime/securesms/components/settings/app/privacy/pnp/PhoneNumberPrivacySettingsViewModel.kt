package org.thoughtcrime.securesms.components.settings.app.privacy.pnp

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.jobs.RefreshOwnProfileJob
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues.PhoneNumberListingMode
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues.PhoneNumberSharingMode
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageSyncHelper

class PhoneNumberPrivacySettingsViewModel : ViewModel() {

  private val _state = mutableStateOf(
    PhoneNumberPrivacySettingsState(
      phoneNumberSharing = SignalStore.phoneNumberPrivacy().isPhoneNumberSharingEnabled,
      discoverableByPhoneNumber = SignalStore.phoneNumberPrivacy().isDiscoverableByPhoneNumber
    )
  )

  val state: State<PhoneNumberPrivacySettingsState> = _state

  fun setNobodyCanSeeMyNumber() {
    setPhoneNumberSharingEnabled(false)
  }

  fun setEveryoneCanSeeMyNumber() {
    setPhoneNumberSharingEnabled(true)
    setDiscoverableByPhoneNumber(true)
  }

  fun setNobodyCanFindMeByMyNumber() {
    setDiscoverableByPhoneNumber(false)
  }

  fun setEveryoneCanFindMeByMyNumber() {
    setDiscoverableByPhoneNumber(true)
  }

  private fun setPhoneNumberSharingEnabled(phoneNumberSharingEnabled: Boolean) {
    SignalStore.phoneNumberPrivacy().phoneNumberSharingMode = if (phoneNumberSharingEnabled) PhoneNumberSharingMode.EVERYBODY else PhoneNumberSharingMode.NOBODY
    SignalDatabase.recipients.markNeedsSync(Recipient.self().id)
    StorageSyncHelper.scheduleSyncForDataChange()
    refresh()
  }

  private fun setDiscoverableByPhoneNumber(discoverable: Boolean) {
    SignalStore.phoneNumberPrivacy().phoneNumberListingMode = if (discoverable) PhoneNumberListingMode.LISTED else PhoneNumberListingMode.UNLISTED
    StorageSyncHelper.scheduleSyncForDataChange()
    ApplicationDependencies.getJobManager().startChain(RefreshAttributesJob()).then(RefreshOwnProfileJob()).enqueue()
    refresh()
  }

  fun refresh() {
    _state.value = PhoneNumberPrivacySettingsState(
      phoneNumberSharing = SignalStore.phoneNumberPrivacy().isPhoneNumberSharingEnabled,
      discoverableByPhoneNumber = SignalStore.phoneNumberPrivacy().isDiscoverableByPhoneNumber
    )
  }
}
