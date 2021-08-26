package org.thoughtcrime.securesms.service.webrtc;

import android.media.AudioManager;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallManager;
import org.signal.ringrtc.GroupCall;
import org.thoughtcrime.securesms.components.webrtc.BroadcastVideoSink;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.CallParticipantId;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.DoNotDisturbUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.util.NetworkUtil;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.UUID;

import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_CONNECTING;
import static org.thoughtcrime.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_RINGING;

/**
 * Process actions to go from incoming "ringing" group call to joining. By the time this processor
 * is running, the group call to ring has been verified to have at least one active device.
 */
public final class IncomingGroupCallActionProcessor extends DeviceAwareActionProcessor {

  private static final String TAG = Log.tag(IncomingGroupCallActionProcessor.class);

  public IncomingGroupCallActionProcessor(WebRtcInteractor webRtcInteractor) {
    super(webRtcInteractor, TAG);
  }

  @Override
  protected @NonNull WebRtcServiceState handleGroupCallRingUpdate(@NonNull WebRtcServiceState currentState,
                                                                  @NonNull RemotePeer remotePeerGroup,
                                                                  @NonNull GroupId.V2 groupId,
                                                                  long ringId,
                                                                  @NonNull UUID uuid,
                                                                  @NonNull CallManager.RingUpdate ringUpdate)
  {
    Log.i(TAG, "handleGroupCallRingUpdate(): recipient: " + remotePeerGroup.getId() + " ring: " + ringId + " update: " + ringUpdate);

    Recipient recipient              = remotePeerGroup.getRecipient();
    boolean   updateForCurrentRingId = ringId == currentState.getCallSetupState().getRingId();
    boolean   isCurrentlyRinging     = currentState.getCallInfoState().getGroupCallState().isRinging();

    if (DatabaseFactory.getGroupCallRingDatabase(context).isCancelled(ringId)) {
      try {
        Log.i(TAG, "Ignoring incoming ring request for already cancelled ring: " + ringId);
        webRtcInteractor.getCallManager().cancelGroupRing(groupId.getDecodedId(), ringId, null);
      } catch (CallException e) {
        Log.w(TAG, "Error while trying to cancel ring: " + ringId, e);
      }
      return currentState;
    }

    if (ringUpdate != CallManager.RingUpdate.REQUESTED) {
      DatabaseFactory.getGroupCallRingDatabase(context).insertOrUpdateGroupRing(ringId, System.currentTimeMillis(), ringUpdate);

      if (updateForCurrentRingId && isCurrentlyRinging) {
        Log.i(TAG, "Cancelling current ring: " + ringId);

        currentState = currentState.builder()
                                   .changeCallInfoState()
                                   .callState(WebRtcViewModel.State.CALL_DISCONNECTED)
                                   .build();

        webRtcInteractor.postStateUpdate(currentState);

        return terminateGroupCall(currentState);
      } else {
        return currentState;
      }
    }

    if (!updateForCurrentRingId && isCurrentlyRinging) {
      try {
        Log.i(TAG, "Already ringing so reply busy for new ring: " + ringId);
        webRtcInteractor.getCallManager().cancelGroupRing(groupId.getDecodedId(), ringId, CallManager.RingCancelReason.Busy);
      } catch (CallException e) {
        Log.w(TAG, "Error while trying to cancel ring: " + ringId, e);
      }
      return currentState;
    }

    if (updateForCurrentRingId) {
      Log.i(TAG, "Already ringing for ring: " + ringId);
      return currentState;
    }

    Log.i(TAG, "Requesting new ring: " + ringId);

    DatabaseFactory.getGroupCallRingDatabase(context).insertGroupRing(ringId, System.currentTimeMillis(), ringUpdate);

    currentState = WebRtcVideoUtil.initializeVideo(context, webRtcInteractor.getCameraEventListener(), currentState);

    AudioManager androidAudioManager = ServiceUtil.getAudioManager(context);
    androidAudioManager.setSpeakerphoneOn(false);

    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_RINGING, remotePeerGroup);
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.INTERACTIVE);

    boolean shouldDisturbUserWithCall = DoNotDisturbUtil.shouldDisturbUserWithCall(context.getApplicationContext());
    if (shouldDisturbUserWithCall) {
      boolean started = webRtcInteractor.startWebRtcCallActivityIfPossible();
      if (!started) {
        Log.i(TAG, "Unable to start call activity due to OS version or not being in the foreground");
        ApplicationDependencies.getAppForegroundObserver().addListener(webRtcInteractor.getForegroundListener());
      }
    }

    webRtcInteractor.initializeAudioForCall();
    if (shouldDisturbUserWithCall && SignalStore.settings().isCallNotificationsEnabled()) {
      Uri                            ringtone     = recipient.resolve().getCallRingtone();
      RecipientDatabase.VibrateState vibrateState = recipient.resolve().getCallVibrate();

      if (ringtone == null) {
        ringtone = SignalStore.settings().getCallRingtone();
      }

      webRtcInteractor.startIncomingRinger(ringtone, vibrateState == RecipientDatabase.VibrateState.ENABLED || (vibrateState == RecipientDatabase.VibrateState.DEFAULT && SignalStore.settings().isCallVibrateEnabled()));
    }

    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_RINGING, remotePeerGroup);
    webRtcInteractor.registerPowerButtonReceiver();

    return currentState.builder()
                       .changeCallSetupState()
                       .isRemoteVideoOffer(true)
                       .ringId(ringId)
                       .ringerRecipient(Recipient.externalPush(context, uuid, null, false))
                       .commit()
                       .changeCallInfoState()
                       .callRecipient(remotePeerGroup.getRecipient())
                       .callState(WebRtcViewModel.State.CALL_INCOMING)
                       .groupCallState(WebRtcViewModel.GroupCallState.RINGING)
                       .putParticipant(remotePeerGroup.getRecipient(),
                                       CallParticipant.createRemote(new CallParticipantId(remotePeerGroup.getRecipient()),
                                                                    remotePeerGroup.getRecipient(),
                                                                    null,
                                                                    new BroadcastVideoSink(currentState.getVideoState().getLockableEglBase(),
                                                                                           false,
                                                                                           true,
                                                                                           currentState.getLocalDeviceState().getOrientation().getDegrees()),
                                                                    true,
                                                                    false,
                                                                    0,
                                                                    true,
                                                                    0,
                                                                    false,
                                                                    CallParticipant.DeviceOrdinal.PRIMARY
                                       ))
                       .build();
  }

  @Override
  protected @NonNull WebRtcServiceState handleAcceptCall(@NonNull WebRtcServiceState currentState, boolean answerWithVideo) {
    byte[] groupId = currentState.getCallInfoState().getCallRecipient().requireGroupId().getDecodedId();
    GroupCall groupCall = webRtcInteractor.getCallManager().createGroupCall(groupId,
                                                                            SignalStore.internalValues().groupCallingServer(),
                                                                            currentState.getVideoState().getLockableEglBase().require(),
                                                                            webRtcInteractor.getGroupCallObserver());

    try {
      groupCall.setOutgoingAudioMuted(true);
      groupCall.setOutgoingVideoMuted(true);
      groupCall.setBandwidthMode(NetworkUtil.getCallingBandwidthMode(context));

      Log.i(TAG, "Connecting to group call: " + currentState.getCallInfoState().getCallRecipient().getId());
      groupCall.connect();
    } catch (CallException e) {
      return groupCallFailure(currentState, "Unable to connect to group call", e);
    }

    currentState = currentState.builder()
                               .changeCallInfoState()
                               .groupCall(groupCall)
                               .groupCallState(WebRtcViewModel.GroupCallState.DISCONNECTED)
                               .commit()
                               .changeCallSetupState()
                               .isRemoteVideoOffer(false)
                               .enableVideoOnCreate(answerWithVideo)
                               .build();

    AudioManager androidAudioManager = ServiceUtil.getAudioManager(context);
    androidAudioManager.setSpeakerphoneOn(false);

    webRtcInteractor.updatePhoneState(WebRtcUtil.getInCallPhoneState(context));
    webRtcInteractor.initializeAudioForCall();
    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_CONNECTING, currentState.getCallInfoState().getCallRecipient());
    webRtcInteractor.setWantsBluetoothConnection(true);

    try {
      groupCall.setOutgoingVideoSource(currentState.getVideoState().requireLocalSink(), currentState.getVideoState().requireCamera());
      groupCall.setOutgoingVideoMuted(answerWithVideo);
      groupCall.setOutgoingAudioMuted(!currentState.getLocalDeviceState().isMicrophoneEnabled());
      groupCall.setBandwidthMode(NetworkUtil.getCallingBandwidthMode(context));

      groupCall.join();
    } catch (CallException e) {
      return groupCallFailure(currentState, "Unable to join group call", e);
    }

    return currentState.builder()
                       .actionProcessor(new GroupJoiningActionProcessor(webRtcInteractor))
                       .changeCallInfoState()
                       .callState(WebRtcViewModel.State.CALL_OUTGOING)
                       .groupCallState(WebRtcViewModel.GroupCallState.CONNECTED_AND_JOINING)
                       .commit()
                       .changeLocalDeviceState()
                       .wantsBluetooth(true)
                       .build();
  }

  @Override
  protected @NonNull WebRtcServiceState handleDenyCall(@NonNull WebRtcServiceState currentState) {
    Recipient         recipient = currentState.getCallInfoState().getCallRecipient();
    Optional<GroupId> groupId   = recipient.getGroupId();
    long              ringId    = currentState.getCallSetupState().getRingId();

    DatabaseFactory.getGroupCallRingDatabase(context).insertOrUpdateGroupRing(ringId,
                                                                              System.currentTimeMillis(),
                                                                              CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE);

    try {
      webRtcInteractor.getCallManager().cancelGroupRing(groupId.get().getDecodedId(),
                                                        ringId,
                                                        CallManager.RingCancelReason.DeclinedByUser);
    } catch (CallException e) {
      Log.w(TAG, "Error while trying to cancel ring " + ringId, e);
    }

    webRtcInteractor.updatePhoneState(LockManager.PhoneState.PROCESSING);
    webRtcInteractor.stopAudio(false);
    webRtcInteractor.setWantsBluetoothConnection(false);
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.IDLE);
    webRtcInteractor.stopForegroundService();

    return WebRtcVideoUtil.deinitializeVideo(currentState)
                          .builder()
                          .actionProcessor(new IdleActionProcessor(webRtcInteractor))
                          .terminate()
                          .build();
  }
}
