package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.core.util.Consumer
import io.reactivex.rxjava3.core.Single
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.identity.IdentityRecordList
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.MultiShareSender
import org.thoughtcrime.securesms.sharing.ShareContact
import org.thoughtcrime.securesms.sharing.ShareContactAndThread
import org.whispersystems.libsignal.util.guava.Optional

class MultiselectForwardRepository(context: Context) {

  private val context = context.applicationContext

  class MultiselectForwardResultHandlers(
    val onAllMessageSentSuccessfully: () -> Unit,
    val onSomeMessagesFailed: () -> Unit,
    val onAllMessagesFailed: () -> Unit
  )

  fun checkForBadIdentityRecords(shareContacts: List<ShareContact>, consumer: Consumer<List<IdentityRecord>>) {
    SignalExecutors.BOUNDED.execute {
      val recipients: List<Recipient> = shareContacts.map { Recipient.resolved(it.recipientId.get()) }
      val identityRecordList: IdentityRecordList = ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecords(recipients)

      consumer.accept(identityRecordList.untrustedRecords)
    }
  }

  fun canSelectRecipient(recipientId: Optional<RecipientId>): Single<Boolean> {
    if (!recipientId.isPresent) {
      return Single.just(true)
    }

    return Single.fromCallable {
      val recipient = Recipient.resolved(recipientId.get())
      if (recipient.isPushV2Group) {
        val record = SignalDatabase.groups.getGroup(recipient.requireGroupId())
        !(record.isPresent && record.get().isAnnouncementGroup && !record.get().isAdmin(Recipient.self()))
      } else {
        true
      }
    }
  }

  fun send(
    additionalMessage: String,
    multiShareArgs: List<MultiShareArgs>,
    shareContacts: List<ShareContact>,
    resultHandlers: MultiselectForwardResultHandlers
  ) {
    SignalExecutors.BOUNDED.execute {
      val threadDatabase: ThreadDatabase = SignalDatabase.threads

      val sharedContactsAndThreads: Set<ShareContactAndThread> = shareContacts
        .asSequence()
        .distinct()
        .filter { it.recipientId.isPresent }
        .map { Recipient.resolved(it.recipientId.get()) }
        .map { ShareContactAndThread(it.id, threadDatabase.getOrCreateThreadIdFor(it), it.isForceSmsSelection) }
        .toSet()

      val mappedArgs: List<MultiShareArgs> = multiShareArgs.map { it.buildUpon(sharedContactsAndThreads).build() }
      val results = mappedArgs.sortedBy { it.timestamp }.map { MultiShareSender.sendSync(it) }

      if (additionalMessage.isNotEmpty()) {
        val additional = MultiShareArgs.Builder(sharedContactsAndThreads)
          .withDraftText(additionalMessage)
          .build()

        val additionalResult: MultiShareSender.MultiShareSendResultCollection = MultiShareSender.sendSync(additional)

        handleResults(results + additionalResult, resultHandlers)
      } else {
        handleResults(results, resultHandlers)
      }
    }
  }

  private fun handleResults(
    results: List<MultiShareSender.MultiShareSendResultCollection>,
    resultHandlers: MultiselectForwardResultHandlers
  ) {
    if (results.any { it.containsFailures() }) {
      if (results.all { it.containsOnlyFailures() }) {
        resultHandlers.onAllMessagesFailed()
      } else {
        resultHandlers.onSomeMessagesFailed()
      }
    } else {
      resultHandlers.onAllMessageSentSuccessfully()
    }
  }

  companion object {
    @JvmStatic
    fun sendNoteToSelf(activity: Activity, args: MultiselectForwardFragmentArgs) {
      val recipient = Recipient.self()
      SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
      MultiselectForwardRepository(activity).send(
        "",
        args.multiShareArgs,
        listOf(ShareContact(Optional.of(recipient.getId()), null)),
        MultiselectForwardRepository.MultiselectForwardResultHandlers(
          onAllMessageSentSuccessfully = { activity.runOnUiThread { Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.MultiselectForwardFragment_messages_sent, args.multiShareArgs.size), Toast.LENGTH_SHORT).show() } },
          onAllMessagesFailed = { activity.runOnUiThread { Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.MultiselectForwardFragment_messages_failed_to_send, args.multiShareArgs.size), Toast.LENGTH_SHORT).show() } },
          onSomeMessagesFailed = { activity.runOnUiThread { Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.MultiselectForwardFragment_messages_sent, args.multiShareArgs.size), Toast.LENGTH_SHORT).show() } }
        )
      )
    }
  }
}
