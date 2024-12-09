package org.thoughtcrime.securesms.keyvalue

import androidx.annotation.CheckResult
import org.thoughtcrime.securesms.database.model.databaseprotos.LocalRegistrationMetadata

class RegistrationValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    private const val REGISTRATION_COMPLETE = "registration.complete"
    private const val PIN_REQUIRED = "registration.pin_required"
    private const val HAS_UPLOADED_PROFILE = "registration.has_uploaded_profile"
    private const val SESSION_E164 = "registration.session_e164"
    private const val SESSION_ID = "registration.session_id"
    private const val SKIPPED_TRANSFER_OR_RESTORE = "registration.has_skipped_transfer_or_restore"
    private const val LOCAL_REGISTRATION_DATA = "registration.local_registration_data"
    private const val RESTORE_COMPLETED = "registration.backup_restore_completed"
    private const val RESTORE_METHOD_TOKEN = "registration.restore_method_token"
    private const val IS_OTHER_DEVICE_ANDROID = "registration.is_other_device_android"
    private const val RESTORING_ON_NEW_DEVICE = "registration.restoring_on_new_device"
  }

  @Synchronized
  public override fun onFirstEverAppLaunch() {
    store
      .beginWrite()
      .putBoolean(HAS_UPLOADED_PROFILE, false)
      .putBoolean(REGISTRATION_COMPLETE, false)
      .putBoolean(PIN_REQUIRED, true)
      .putBoolean(SKIPPED_TRANSFER_OR_RESTORE, false)
      .commit()
  }

  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  @Synchronized
  fun clearRegistrationComplete() {
    onFirstEverAppLaunch()
  }

  @Synchronized
  fun markRegistrationComplete() {
    store
      .beginWrite()
      .putBoolean(REGISTRATION_COMPLETE, true)
      .commit()
  }

  @CheckResult
  @Synchronized
  fun pinWasRequiredAtRegistration(): Boolean {
    return store.getBoolean(PIN_REQUIRED, false)
  }

  @get:Synchronized
  @get:CheckResult
  val isRegistrationComplete: Boolean by booleanValue(REGISTRATION_COMPLETE, true)

  var localRegistrationMetadata: LocalRegistrationMetadata? by protoValue(LOCAL_REGISTRATION_DATA, LocalRegistrationMetadata.ADAPTER)

  @get:JvmName("hasUploadedProfile")
  var hasUploadedProfile: Boolean by booleanValue(HAS_UPLOADED_PROFILE, true)
  var sessionId: String? by stringValue(SESSION_ID, null)
  var sessionE164: String? by stringValue(SESSION_E164, null)

  var isOtherDeviceAndroid: Boolean by booleanValue(IS_OTHER_DEVICE_ANDROID, false)
  var restoreMethodToken: String? by stringValue(RESTORE_METHOD_TOKEN, null)

  @get:JvmName("isRestoringOnNewDevice")
  var restoringOnNewDevice: Boolean by booleanValue(RESTORING_ON_NEW_DEVICE, false)

  fun hasSkippedTransferOrRestore(): Boolean {
    return getBoolean(SKIPPED_TRANSFER_OR_RESTORE, false)
  }

  fun markSkippedTransferOrRestore() {
    putBoolean(SKIPPED_TRANSFER_OR_RESTORE, true)
  }

  fun debugClearSkippedTransferOrRestore() {
    putBoolean(SKIPPED_TRANSFER_OR_RESTORE, false)
  }

  fun hasCompletedRestore(): Boolean {
    return getBoolean(RESTORE_COMPLETED, false)
  }

  fun markRestoreCompleted() {
    putBoolean(RESTORE_COMPLETED, true)
  }
}
