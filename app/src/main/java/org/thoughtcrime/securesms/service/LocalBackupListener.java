package org.thoughtcrime.securesms.service;


import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.jobs.LocalBackupJob;
import org.thoughtcrime.securesms.keyvalue.SettingsValues;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.JavaTimeExtensionsKt;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class LocalBackupListener extends PersistentAlarmManagerListener {

  private static final int BACKUP_JITTER_WINDOW_SECONDS = Math.toIntExact(TimeUnit.MINUTES.toSeconds(10));

  @Override
  protected boolean shouldScheduleExact() {
    return true;
  }

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
      new LocalBackupListener().onReceive(context, getScheduleIntent());
    }
  }

  public static long setNextBackupTimeToIntervalFromNow(@NonNull Context context) {
    LocalDateTime now    = LocalDateTime.now();
    int           hour   = SignalStore.settings().getBackupHour();
    int           minute = SignalStore.settings().getBackupMinute();
    LocalDateTime next   = now.withHour(hour).withMinute(minute).withSecond(0);

    int jitter = (new Random().nextInt(BACKUP_JITTER_WINDOW_SECONDS)) - (BACKUP_JITTER_WINDOW_SECONDS / 2);

    next.plusSeconds(jitter);

    if (now.isAfter(next)) {
      next = next.plusDays(TextSecurePreferences.getBackupIntervalInDays(context));
    }

    long nextTime = JavaTimeExtensionsKt.toMillis(next);

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
