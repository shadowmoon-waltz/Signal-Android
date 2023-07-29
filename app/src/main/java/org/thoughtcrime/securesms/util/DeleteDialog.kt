package org.thoughtcrime.securesms.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask

object DeleteDialog {

  /**
   * Displays a deletion dialog for the given set of message records.
   *
   * @param context           Android Context
   * @param messageRecords    The message records to delete
   * @param title             The dialog title
   * @param message           The dialog message, or null
   * @param forceRemoteDelete Allow remote deletion, even if it would normally be disallowed
   * @param forceDeleteForMe  no prompt, go ahead and delete for me
   * @param checkFastDeleteForMe skip prompt if trashNoPromptForMe is true
   *
   * @return a Single, who's value notes whether or not a thread deletion occurred.
   */
  fun show(
    context: Context,
    messageRecords: Set<MessageRecord>,
    title: CharSequence = context.resources.getQuantityString(R.plurals.ConversationFragment_delete_selected_messages, messageRecords.size, messageRecords.size),
    message: CharSequence? = null,
    forceRemoteDelete: Boolean = false,
    forceDeleteForMe: Boolean = false,
    checkFastDeleteForMe: Boolean = false
  ): Single<Boolean> = Single.create { emitter ->
    if (forceDeleteForMe || (checkFastDeleteForMe && SignalStore.settings().isTrashNoPromptForMe())) {
      handleDeleteForMe(context, messageRecords, emitter)
      return@create
    }

    val builder = MaterialAlertDialogBuilder(context)

    builder.setTitle(title)
    builder.setMessage(message)
    builder.setCancelable(true)

    val isNoteToSelfDelete = isNoteToSelfDelete(messageRecords)

    if (forceRemoteDelete) {
      builder.setPositiveButton(R.string.ConversationFragment_delete_for_everyone) { _, _ -> deleteForEveryone(messageRecords, emitter) }
    } else {
      builder.setPositiveButton(if (isNoteToSelfDelete) R.string.ConversationFragment_delete_on_this_device else R.string.ConversationFragment_delete_for_me) { _, _ ->
        handleDeleteForMe(context, messageRecords, emitter)
      }

      if (MessageConstraintsUtil.isValidRemoteDeleteSend(messageRecords, System.currentTimeMillis()) && (!isNoteToSelfDelete || TextSecurePreferences.isMultiDevice(context))) {
        builder.setNeutralButton(R.string.ConversationFragment_delete_for_everyone) { _, _ -> handleDeleteForEveryone(context, messageRecords, emitter) }
      }
    }

    builder.setNegativeButton(android.R.string.cancel) { _, _ -> emitter.onSuccess(false) }
    builder.setOnCancelListener { emitter.onSuccess(false) }
    builder.show()
  }

  private fun isNoteToSelfDelete(messageRecords: Set<MessageRecord>): Boolean {
    return messageRecords.all { messageRecord: MessageRecord -> messageRecord.isOutgoing && messageRecord.toRecipient.isSelf }
  }

  private fun handleDeleteForMe(context: Context, messageRecords: Set<MessageRecord>, emitter: SingleEmitter<Boolean>) {
    if (messageRecords.size > 1) {
      DeleteProgressDialogAsyncTask(context, messageRecords, emitter::onSuccess).executeOnExecutor(SignalExecutors.BOUNDED)
    } else {
      SignalExecutors.BOUNDED.execute { emitter.onSuccess(SignalDatabase.messages.deleteMessage(messageRecords.first().id)) }
    }
  }

  private fun handleDeleteForEveryone(context: Context, messageRecords: Set<MessageRecord>, emitter: SingleEmitter<Boolean>) {
    if (SignalStore.uiHints().hasConfirmedDeleteForEveryoneOnce() || isNoteToSelfDelete(messageRecords)) {
      deleteForEveryone(messageRecords, emitter)
    } else {
      MaterialAlertDialogBuilder(context)
        .setMessage(R.string.ConversationFragment_this_message_will_be_deleted_for_everyone_in_the_conversation)
        .setPositiveButton(R.string.ConversationFragment_delete_for_everyone) { _, _ ->
          SignalStore.uiHints().markHasConfirmedDeleteForEveryoneOnce()
          deleteForEveryone(messageRecords, emitter)
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> emitter.onSuccess(false) }
        .setOnCancelListener { emitter.onSuccess(false) }
        .show()
    }
  }

  private fun deleteForEveryone(messageRecords: Set<MessageRecord>, emitter: SingleEmitter<Boolean>) {
    SignalExecutors.BOUNDED.execute {
      messageRecords.forEach { message ->
        MessageSender.sendRemoteDelete(message.id)
      }

      emitter.onSuccess(false)
    }
  }

  private class DeleteProgressDialogAsyncTask(
    context: Context,
    private val messageRecords: Set<MessageRecord>,
    private val onDeletionCompleted: ((Boolean) -> Unit)
  ) : ProgressDialogAsyncTask<Void, Void, Boolean>(
    context,
    R.string.ConversationFragment_deleting,
    R.string.ConversationFragment_deleting_messages
  ) {
    override fun doInBackground(vararg params: Void?): Boolean {
      return messageRecords.map { record ->
        if (record.isMms) {
          SignalDatabase.messages.deleteMessage(record.id)
        } else {
          SignalDatabase.messages.deleteMessage(record.id)
        }
      }.any { it }
    }

    override fun onPostExecute(result: Boolean?) {
      super.onPostExecute(result)
      onDeletionCompleted(result == true)
    }
  }
}
