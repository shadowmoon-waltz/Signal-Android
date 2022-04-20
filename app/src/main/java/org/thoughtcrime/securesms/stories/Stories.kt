package org.thoughtcrime.securesms.stories

import androidx.annotation.WorkerThread
import androidx.fragment.app.FragmentManager
import io.reactivex.rxjava3.core.Completable
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.HeaderAction
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediasend.v2.stories.ChooseStoryTypeBottomSheet
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.FeatureFlags
import java.util.concurrent.TimeUnit

object Stories {

  const val MAX_BODY_SIZE = 700

  @JvmField
  val MAX_VIDEO_DURATION_MILLIS = TimeUnit.SECONDS.toMillis(30)

  @JvmStatic
  fun isFeatureAvailable(): Boolean {
    return FeatureFlags.stories() && Recipient.self().storiesCapability == Recipient.Capability.SUPPORTED
  }

  @JvmStatic
  fun isFeatureEnabled(): Boolean {
    return isFeatureAvailable() && !SignalStore.storyValues().isFeatureDisabled
  }

  fun getHeaderAction(fragmentManager: FragmentManager): HeaderAction {
    return HeaderAction(
      R.string.ContactsCursorLoader_new_story,
      R.drawable.ic_plus_20
    ) {
      ChooseStoryTypeBottomSheet().show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }

  @WorkerThread
  fun sendTextStories(messages: List<OutgoingSecureMediaMessage>): Completable {
    return Completable.create { emitter ->
      MessageSender.sendMediaBroadcast(ApplicationDependencies.getApplication(), messages, listOf(), listOf())
      emitter.onComplete()
    }
  }

  @JvmStatic
  fun getRecipientsToSendTo(messageId: Long, sentTimestamp: Long, allowsReplies: Boolean): List<Recipient> {
    val recipientIds: List<RecipientId> = SignalDatabase.storySends.getRecipientsToSendTo(messageId, sentTimestamp, allowsReplies)

    return RecipientUtil.getEligibleForSending(recipientIds.map(Recipient::resolved))
  }

  @WorkerThread
  fun onStorySettingsChanged(distributionListId: DistributionListId) {
    val recipientId = SignalDatabase.distributionLists.getRecipientId(distributionListId) ?: error("Cannot find recipient id for distribution list.")
    onStorySettingsChanged(recipientId)
  }

  @WorkerThread
  fun onStorySettingsChanged(storyRecipientId: RecipientId) {
    SignalDatabase.recipients.markNeedsSync(storyRecipientId)
    StorageSyncHelper.scheduleSyncForDataChange()
  }
}
