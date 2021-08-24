package org.thoughtcrime.securesms.crypto.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.SessionDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.signalservice.api.SignalServiceSessionStore;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TextSecureSessionStore implements SignalServiceSessionStore {

  private static final String TAG = Log.tag(TextSecureSessionStore.class);

  private static final Object LOCK = new Object();

  @NonNull  private final Context context;

  public TextSecureSessionStore(@NonNull Context context) {
    this.context = context;
  }

  @Override
  public SessionRecord loadSession(@NonNull SignalProtocolAddress address) {
    synchronized (LOCK) {
      SessionRecord sessionRecord = DatabaseFactory.getSessionDatabase(context).load(address);

      if (sessionRecord == null) {
        Log.w(TAG, "No existing session information found.");
        return new SessionRecord();
      }

      return sessionRecord;
    }
  }

  @Override
  public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
    synchronized (LOCK) {
      List<SessionRecord> sessionRecords = DatabaseFactory.getSessionDatabase(context).load(addresses);

      if (sessionRecords.size() != addresses.size()) {
        String message = "Mismatch! Asked for " + addresses.size() + " sessions, but only found " + sessionRecords.size() + "!";
        Log.w(TAG, message);
        throw new NoSessionException(message);
      }

      return sessionRecords;
    }
  }

  @Override
  public void storeSession(@NonNull SignalProtocolAddress address, @NonNull SessionRecord record) {
    synchronized (LOCK) {
      DatabaseFactory.getSessionDatabase(context).store(address, record);
    }
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    synchronized (LOCK) {
      SessionRecord sessionRecord = DatabaseFactory.getSessionDatabase(context).load(address);

      return sessionRecord != null &&
             sessionRecord.hasSenderChain() &&
             sessionRecord.getSessionVersion() == CiphertextMessage.CURRENT_VERSION;
    }
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    synchronized (LOCK) {
      DatabaseFactory.getSessionDatabase(context).delete(address);
    }
  }

  @Override
  public void deleteAllSessions(String name) {
    synchronized (LOCK) {
      DatabaseFactory.getSessionDatabase(context).deleteAllFor(name);
    }
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    synchronized (LOCK) {
      return DatabaseFactory.getSessionDatabase(context).getSubDevices(name);
    }
  }

  @Override
  public Set<SignalProtocolAddress> getAllAddressesWithActiveSessions(List<String> addressNames) {
    synchronized (LOCK) {
      List<SessionDatabase.SessionRow> rows = DatabaseFactory.getSessionDatabase(context).getAllFor(addressNames);
      return rows.stream()
                 .filter(row -> isActive(row.getRecord()))
                 .map(row -> new SignalProtocolAddress(row.getAddress(), row.getDeviceId()))
                 .collect(Collectors.toSet());
    }
  }

  @Override
  public void archiveSession(SignalProtocolAddress address) {
    synchronized (LOCK) {
      SessionRecord session = DatabaseFactory.getSessionDatabase(context).load(address);
      if (session != null) {
        session.archiveCurrentState();
        DatabaseFactory.getSessionDatabase(context).store(address, session);
      }
    }
  }

  public void archiveSession(@NonNull RecipientId recipientId, int deviceId) {
    synchronized (LOCK) {
      Recipient recipient = Recipient.resolved(recipientId);

      if (recipient.hasUuid()) {
        archiveSession(new SignalProtocolAddress(recipient.requireUuid().toString(), deviceId));
      }

      if (recipient.hasE164()) {
        archiveSession(new SignalProtocolAddress(recipient.requireE164(), deviceId));
      }
    }
  }

  public void archiveSiblingSessions(@NonNull SignalProtocolAddress address) {
    synchronized (LOCK) {
      List<SessionDatabase.SessionRow> sessions = DatabaseFactory.getSessionDatabase(context).getAllFor(address.getName());

      for (SessionDatabase.SessionRow row : sessions) {
        if (row.getDeviceId() != address.getDeviceId()) {
          row.getRecord().archiveCurrentState();
          storeSession(new SignalProtocolAddress(row.getAddress(), row.getDeviceId()), row.getRecord());
        }
      }
    }
  }

  public void archiveAllSessions() {
    synchronized (LOCK) {
      List<SessionDatabase.SessionRow> sessions = DatabaseFactory.getSessionDatabase(context).getAll();

      for (SessionDatabase.SessionRow row : sessions) {
        row.getRecord().archiveCurrentState();
        storeSession(new SignalProtocolAddress(row.getAddress(), row.getDeviceId()), row.getRecord());
      }
    }
  }

  private static boolean isActive(@Nullable SessionRecord record) {
    return record != null &&
           record.hasSenderChain() &&
           record.getSessionVersion() == CiphertextMessage.CURRENT_VERSION;
  }
}
