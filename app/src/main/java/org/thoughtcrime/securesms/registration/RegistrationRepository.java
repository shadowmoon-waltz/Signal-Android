package org.thoughtcrime.securesms.registration;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.util.logging.Log;
import org.signal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.RotateCertificateJob;
import org.thoughtcrime.securesms.pin.PinState;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.registration.VerifyAccountRepository.VerifyAccountWithRegistrationLockResponse;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.KbsPinData;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Operations required for finalizing the registration of an account. This is
 * to be used after verifying the code and registration lock (if necessary) with
 * the server and being issued a UUID.
 */
public final class RegistrationRepository {

  private static final String TAG = Log.tag(RegistrationRepository.class);

  private final Application context;

  public RegistrationRepository(@NonNull Application context) {
    this.context = context;
  }

  public int getRegistrationId() {
    int registrationId = TextSecurePreferences.getLocalRegistrationId(context);
    if (registrationId == 0) {
      registrationId = KeyHelper.generateRegistrationId(false);
      TextSecurePreferences.setLocalRegistrationId(context, registrationId);
    }
    return registrationId;
  }

  public @NonNull ProfileKey getProfileKey(@NonNull String e164) {
    ProfileKey profileKey = findExistingProfileKey(context, e164);

    if (profileKey == null) {
      profileKey = ProfileKeyUtil.createNew();
      Log.i(TAG, "No profile key found, created a new one");
    }

    return profileKey;
  }

  public Single<ServiceResponse<VerifyAccountResponse>> registerAccountWithoutRegistrationLock(@NonNull RegistrationData registrationData,
                                                                                               @NonNull VerifyAccountResponse response)
  {
    return registerAccount(registrationData, response, null, null);
  }

  public Single<ServiceResponse<VerifyAccountResponse>> registerAccountWithRegistrationLock(@NonNull RegistrationData registrationData,
                                                                                            @NonNull VerifyAccountWithRegistrationLockResponse response,
                                                                                            @NonNull String pin)
  {
    return registerAccount(registrationData, response.getVerifyAccountResponse(), pin, response.getKbsData());
  }

  private Single<ServiceResponse<VerifyAccountResponse>> registerAccount(@NonNull RegistrationData registrationData,
                                                                         @NonNull VerifyAccountResponse response,
                                                                         @Nullable String pin,
                                                                         @Nullable KbsPinData kbsData)
  {
    return Single.<ServiceResponse<VerifyAccountResponse>>fromCallable(() -> {
      try {
        registerAccountInternal(registrationData, response, pin, kbsData);

        JobManager jobManager = ApplicationDependencies.getJobManager();
        jobManager.add(new DirectoryRefreshJob(false));
        jobManager.add(new RotateCertificateJob());

        DirectoryRefreshListener.schedule(context);
        RotateSignedPreKeyListener.schedule(context);

        return ServiceResponse.forResult(response, 200, null);
      } catch (IOException e) {
        return ServiceResponse.forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  @WorkerThread
  private void registerAccountInternal(@NonNull RegistrationData registrationData,
                                       @NonNull VerifyAccountResponse response,
                                       @Nullable String pin,
                                       @Nullable KbsPinData kbsData)
      throws IOException
  {
    SessionUtil.archiveAllSessions();
    SenderKeyUtil.clearAllState(context);

    UUID    uuid   = UuidUtil.parseOrThrow(response.getUuid());
    boolean hasPin = response.isStorageCapable();

    IdentityKeyPair    identityKey  = IdentityKeyUtil.getIdentityKeyPair(context);
    List<PreKeyRecord> records      = PreKeyUtil.generatePreKeys(context);
    SignedPreKeyRecord signedPreKey = PreKeyUtil.generateSignedPreKey(context, identityKey, true);

    SignalServiceAccountManager accountManager = AccountManagerFactory.createAuthenticated(context, uuid, registrationData.getE164(), registrationData.getPassword());
    accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKey, records);

    if (registrationData.isFcm()) {
      accountManager.setGcmId(Optional.fromNullable(registrationData.getFcmToken()));
    }

    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    RecipientId       selfId            = Recipient.externalPush(context, uuid, registrationData.getE164(), true).getId();

    recipientDatabase.setProfileSharing(selfId, true);
    recipientDatabase.markRegisteredOrThrow(selfId, uuid);

    TextSecurePreferences.setLocalNumber(context, registrationData.getE164());
    TextSecurePreferences.setLocalUuid(context, uuid);
    recipientDatabase.setProfileKey(selfId, registrationData.getProfileKey());
    ApplicationDependencies.getRecipientCache().clearSelf();

    TextSecurePreferences.setFcmToken(context, registrationData.getFcmToken());
    TextSecurePreferences.setFcmDisabled(context, registrationData.isNotFcm());
    TextSecurePreferences.setWebsocketRegistered(context, true);

    ApplicationDependencies.getIdentityStore()
                           .saveIdentityWithoutSideEffects(selfId,
                                                           identityKey.getPublicKey(),
                                                           IdentityDatabase.VerifiedStatus.VERIFIED,
                                                           true,
                                                           System.currentTimeMillis(),
                                                           true);

    TextSecurePreferences.setPushRegistered(context, true);
    TextSecurePreferences.setPushServerPassword(context, registrationData.getPassword());
    TextSecurePreferences.setSignedPreKeyRegistered(context, true);
    TextSecurePreferences.setPromptedPushRegistration(context, true);
    TextSecurePreferences.setUnauthorizedReceived(context, false);

    PinState.onRegistration(context, kbsData, pin, hasPin);
  }

  @WorkerThread
  private static @Nullable ProfileKey findExistingProfileKey(@NonNull Context context, @NonNull String e164number) {
    RecipientDatabase     recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    Optional<RecipientId> recipient         = recipientDatabase.getByE164(e164number);

    if (recipient.isPresent()) {
      return ProfileKeyUtil.profileKeyOrNull(Recipient.resolved(recipient.get()).getProfileKey());
    }

    return null;
  }
}
