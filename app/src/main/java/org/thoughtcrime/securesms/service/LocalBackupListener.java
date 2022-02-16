package org.thoughtcrime.securesms.service;


import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.jobs.LocalBackupJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.concurrent.TimeUnit;

public class LocalBackupListener extends PersistentAlarmManagerListener {

  @Override
  protected long getNextScheduledExecutionTime(Context context) {
    return TextSecurePreferences.getNextBackupTime(context);
  }

  @Override
  protected long onAlarm(Context context, long scheduledTime) {
    if (SignalStore.settings().isBackupEnabled()) {
      LocalBackupJob.enqueue(false);
    }

    return setNextBackupTimeToIntervalFromNow(context);
  }

  public static void schedule(Context context) {
    if (SignalStore.settings().isBackupEnabled()) {
      new LocalBackupListener().onReceive(context, new Intent());
    }
  }

  public static long setNextBackupTimeToIntervalFromNow(@NonNull Context context) {
    long nextTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(TextSecurePreferences.getBackupIntervalInDays(context));
    TextSecurePreferences.setNextBackupTime(context, nextTime);

    return nextTime;
  }

  public static void setNextBackupTimeToIntervalFromPrevious(@NonNull Context context, int oldInterval) {
    int days = TextSecurePreferences.getBackupIntervalInDays(context);
    long adjust = (days >= oldInterval) ? TimeUnit.DAYS.toMillis(days - oldInterval) : -TimeUnit.DAYS.toMillis(oldInterval - days);
    long nextTime = TextSecurePreferences.getNextBackupTime(context) + adjust;
    TextSecurePreferences.setNextBackupTime(context, nextTime);
  }
}
