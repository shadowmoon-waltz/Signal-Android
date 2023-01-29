package org.thoughtcrime.securesms.service;


import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.jobs.LocalBackupJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.JavaTimeExtensionsKt;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

public class LocalBackupListener extends PersistentAlarmManagerListener {

  @Override
  protected boolean shouldScheduleExact() {
    return Build.VERSION.SDK_INT >= 31;
  }

  @Override
  protected long getNextScheduledExecutionTime(Context context) {
    return TextSecurePreferences.getNextBackupTime(context);
  }

  @Override
  protected long onAlarm(Context context, long scheduledTime) {
    if (SignalStore.settings().isBackupEnabled()) {
      LocalBackupJob.enqueue(shouldScheduleExact());
    }

    return setNextBackupTimeToIntervalFromNow(context);
  }

  public static void schedule(Context context) {
    if (SignalStore.settings().isBackupEnabled()) {
      new LocalBackupListener().onReceive(context, getScheduleIntent());
    }
  }

  public static long setNextBackupTimeToIntervalFromNow(@NonNull Context context) {
    long nextTime;

    if (Build.VERSION.SDK_INT < 31) {
      nextTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(TextSecurePreferences.getBackupIntervalInDays(context));
    } else {
      LocalDateTime now  = LocalDateTime.now();
      int hour = SignalStore.settings().getBackupHour();
      int minute = SignalStore.settings().getBackupMinute();
      LocalDateTime next = now.withHour(hour).withMinute(minute).withSecond(0);
      if (now.getHour() >= 2) {
        next = next.plusDays(TextSecurePreferences.getBackupIntervalInDays(context));
      }

      nextTime = JavaTimeExtensionsKt.toMillis(next);
    }

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
