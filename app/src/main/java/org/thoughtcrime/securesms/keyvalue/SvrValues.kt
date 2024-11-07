package org.thoughtcrime.securesms.keyvalue

import org.signal.core.util.StringStringSerializer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.JsonUtils
import org.whispersystems.signalservice.api.kbs.MasterKey
import org.whispersystems.signalservice.api.kbs.PinHashUtil.localPinHash
import org.whispersystems.signalservice.internal.contacts.entities.TokenResponse
import java.io.IOException
import java.security.SecureRandom

class SvrValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private val TAG = Log.tag(SvrValues::class)

    const val REGISTRATION_LOCK_ENABLED: String = "kbs.v2_lock_enabled"
    const val OPTED_OUT: String = "kbs.opted_out"

    private const val MASTER_KEY = "kbs.registration_lock_master_key"
    private const val TOKEN_RESPONSE = "kbs.token_response"
    private const val PIN = "kbs.pin"
    private const val LOCK_LOCAL_PIN_HASH = "kbs.registration_lock_local_pin_hash"
    private const val LAST_CREATE_FAILED_TIMESTAMP = "kbs.last_create_failed_timestamp"
    private const val PIN_FORGOTTEN_OR_SKIPPED = "kbs.pin.forgotten.or.skipped"
    private const val SVR2_AUTH_TOKENS = "kbs.kbs_auth_tokens"
    private const val SVR_LAST_AUTH_REFRESH_TIMESTAMP = "kbs.kbs_auth_tokens.last_refresh_timestamp"
    private const val SVR3_AUTH_TOKENS = "kbs.svr3_auth_tokens"
  }

  public override fun onFirstEverAppLaunch() = Unit

  public override fun getKeysToIncludeInBackup(): List<String> {
    return listOf(
      SVR2_AUTH_TOKENS,
      SVR3_AUTH_TOKENS
    )
  }

  /** Deliberately does not clear the [MASTER_KEY]. */
  @Synchronized
  fun clearRegistrationLockAndPin() {
    store.beginWrite()
      .remove(REGISTRATION_LOCK_ENABLED)
      .remove(TOKEN_RESPONSE)
      .remove(LOCK_LOCAL_PIN_HASH)
      .remove(PIN)
      .remove(LAST_CREATE_FAILED_TIMESTAMP)
      .remove(OPTED_OUT)
      .remove(SVR2_AUTH_TOKENS)
      .remove(SVR_LAST_AUTH_REFRESH_TIMESTAMP)
      .commit()
  }

  @Synchronized
  fun setMasterKey(masterKey: MasterKey, pin: String) {
    store.beginWrite()
      .putBlob(MASTER_KEY, masterKey.serialize())
      .putString(LOCK_LOCAL_PIN_HASH, localPinHash(pin))
      .putString(PIN, pin)
      .putLong(LAST_CREATE_FAILED_TIMESTAMP, -1)
      .putBoolean(OPTED_OUT, false)
      .commit()
  }

  @Synchronized
  fun setPinIfNotPresent(pin: String) {
    if (store.getString(PIN, null) == null) {
      store.beginWrite().putString(PIN, pin).commit()
    }
  }

  /** Whether or not registration lock V2 is enabled. */
  @get:Synchronized
  @set:Synchronized
  var isRegistrationLockEnabled: Boolean by booleanValue(REGISTRATION_LOCK_ENABLED, false)

  @Synchronized
  fun onPinCreateFailure() {
    putLong(LAST_CREATE_FAILED_TIMESTAMP, System.currentTimeMillis())
  }

  /** Whether or not the last time the user attempted to create a PIN, it failed. */
  @Synchronized
  fun lastPinCreateFailed(): Boolean {
    return getLong(LAST_CREATE_FAILED_TIMESTAMP, -1) > 0
  }

  @get:Synchronized
  val masterKey: MasterKey
    /** Returns the Master Key, lazily creating one if needed. */
    get() {
      val blob = store.getBlob(MASTER_KEY, null)
      if (blob != null) {
        return MasterKey(blob)
      }

      Log.i(TAG, "Generating Master Key...", Throwable())
      val masterKey = MasterKey.createNew(SecureRandom())
      store.beginWrite().putBlob(MASTER_KEY, masterKey.serialize()).commit()
      return masterKey
    }

  @get:Synchronized
  val pinBackedMasterKey: MasterKey?
    /** Returns null if master key is not backed up by a pin. */
    get() {
      if (!isRegistrationLockEnabled) return null
      return rawMasterKey
    }

  @get:Synchronized
  private val rawMasterKey: MasterKey?
    get() = getBlob(MASTER_KEY, null)?.let { MasterKey(it) }

  @get:Synchronized
  val registrationLockToken: String?
    get() {
      val masterKey = pinBackedMasterKey
      return masterKey?.deriveRegistrationLock()
    }

  @get:Synchronized
  val recoveryPassword: String?
    get() {
      val masterKey = rawMasterKey
      return if (masterKey != null && hasPin()) {
        masterKey.deriveRegistrationRecoveryPassword()
      } else {
        null
      }
    }

  @get:Synchronized
  val pin: String? by stringValue(PIN, null)

  @get:Synchronized
  val localPinHash: String? by stringValue(LOCK_LOCAL_PIN_HASH, null)

  @Synchronized
  fun hasPin(): Boolean {
    return localPinHash != null
  }

  @get:Synchronized
  @set:Synchronized
  var isPinForgottenOrSkipped: Boolean by booleanValue(PIN_FORGOTTEN_OR_SKIPPED, false)

  @Synchronized
  fun putSvr2AuthTokens(tokens: List<String>) {
    putList(SVR2_AUTH_TOKENS, tokens, StringStringSerializer)
    lastRefreshAuthTimestamp = System.currentTimeMillis()
  }

  @Synchronized
  fun putSvr3AuthTokens(tokens: List<String>) {
    putList(SVR3_AUTH_TOKENS, tokens, StringStringSerializer)
    lastRefreshAuthTimestamp = System.currentTimeMillis()
  }

  @get:Synchronized
  val svr2AuthTokens: List<String>
    get() = getList(SVR2_AUTH_TOKENS, StringStringSerializer).requireNoNulls()

  @get:Synchronized
  val svr3AuthTokens: List<String>
    get() = getList(SVR3_AUTH_TOKENS, StringStringSerializer).requireNoNulls()

  /**
   * Keeps the 10 most recent KBS auth tokens.
   * @param token
   * @return whether the token was added (new) or ignored (already existed)
   */
  @Synchronized
  fun appendSvr2AuthTokenToList(token: String): Boolean {
    val tokens = svr2AuthTokens
    if (tokens.contains(token)) {
      return false
    } else {
      val result = (listOf(token) + tokens).take(10)
      putSvr2AuthTokens(result)
      return true
    }
  }

  /**
   * Keeps the 10 most recent SVR3 auth tokens.
   * @param token
   * @return whether the token was added (new) or ignored (already existed)
   */
  @Synchronized
  fun appendSvr3AuthTokenToList(token: String): Boolean {
    val tokens = svr3AuthTokens
    if (tokens.contains(token)) {
      return false
    } else {
      val result = (listOf(token) + tokens).take(10)
      putSvr3AuthTokens(result)
      return true
    }
  }

  @Synchronized
  fun removeSvr2AuthTokens(invalid: List<String>): Boolean {
    val tokens: MutableList<String> = ArrayList(svr2AuthTokens)
    if (tokens.removeAll(invalid)) {
      putSvr2AuthTokens(tokens)
      return true
    }

    return false
  }

  @Synchronized
  fun removeSvr3AuthTokens(invalid: List<String>): Boolean {
    val tokens: MutableList<String> = ArrayList(svr3AuthTokens)
    if (tokens.removeAll(invalid)) {
      putSvr3AuthTokens(tokens)
      return true
    }

    return false
  }

  @Synchronized
  fun optOut() {
    store.beginWrite()
      .putBoolean(OPTED_OUT, true)
      .remove(TOKEN_RESPONSE)
      .putBlob(MASTER_KEY, MasterKey.createNew(SecureRandom()).serialize())
      .remove(LOCK_LOCAL_PIN_HASH)
      .remove(PIN)
      .putLong(LAST_CREATE_FAILED_TIMESTAMP, -1)
      .commit()
  }

  @Synchronized
  fun hasOptedOut(): Boolean {
    return getBoolean(OPTED_OUT, false)
  }

  @get:Synchronized
  val registrationLockTokenResponse: TokenResponse?
    get() {
      val token = store.getString(TOKEN_RESPONSE, null) ?: return null

      try {
        return JsonUtils.fromJson(token, TokenResponse::class.java)
      } catch (e: IOException) {
        throw AssertionError(e)
      }
    }

  var lastRefreshAuthTimestamp: Long by longValue(SVR_LAST_AUTH_REFRESH_TIMESTAMP, 0L)
}
