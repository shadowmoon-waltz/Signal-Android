/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import org.greenrobot.eventbus.EventBus
import org.signal.core.util.Base64
import org.signal.core.util.Hex
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.InvalidMacException
import org.signal.libsignal.protocol.InvalidMessageException
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.events.PartProgressEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobLogger.format
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.notifications.v2.ConversationId.Companion.forConversation
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.thoughtcrime.securesms.util.FeatureFlags
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.signalservice.api.backup.MediaName
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException
import org.whispersystems.signalservice.api.push.exceptions.RangeException
import java.io.File
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Download attachment from locations as specified in their record.
 */
class RestoreAttachmentJob private constructor(
  parameters: Parameters,
  private val messageId: Long,
  attachmentId: AttachmentId,
  private val manual: Boolean,
  private var forceArchiveDownload: Boolean,
  private val fullSize: Boolean
) : BaseJob(parameters) {

  companion object {
    const val KEY = "RestoreAttachmentJob"
    private val TAG = Log.tag(AttachmentDownloadJob::class.java)

    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_ATTACHMENT_ID = "part_row_id"
    private const val KEY_MANUAL = "part_manual"
    private const val KEY_FORCE_ARCHIVE = "force_archive"
    private const val KEY_FULL_SIZE = "full_size"

    @JvmStatic
    fun constructQueueString(attachmentId: AttachmentId): String {
      // TODO: decide how many queues
      return "RestoreAttachmentJob"
    }

    fun jobSpecMatchesAnyAttachmentId(jobSpec: JobSpec, ids: Set<AttachmentId>): Boolean {
      if (KEY != jobSpec.factoryKey) {
        return false
      }

      val serializedData = jobSpec.serializedData ?: return false
      val data = JsonJobData.deserialize(serializedData)
      val parsed = AttachmentId(data.getLong(KEY_ATTACHMENT_ID))
      return ids.contains(parsed)
    }

    fun modifyPriorities(ids: Set<AttachmentId>, priority: Int) {
      val jobManager = ApplicationDependencies.getJobManager()
      jobManager.update { spec ->
        if (jobSpecMatchesAnyAttachmentId(spec, ids) && spec.priority != priority) {
          spec.copy(priority = priority)
        } else {
          spec
        }
      }
    }
  }

  private val attachmentId: Long

  constructor(messageId: Long, attachmentId: AttachmentId, manual: Boolean, forceArchiveDownload: Boolean = false, fullSize: Boolean = true) : this(
    Parameters.Builder()
      .setQueue(constructQueueString(attachmentId))
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build(),
    messageId,
    attachmentId,
    manual,
    forceArchiveDownload,
    fullSize
  )

  init {
    this.attachmentId = attachmentId.id
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(KEY_MESSAGE_ID, messageId)
      .putLong(KEY_ATTACHMENT_ID, attachmentId)
      .putBoolean(KEY_MANUAL, manual)
      .putBoolean(KEY_FORCE_ARCHIVE, forceArchiveDownload)
      .putBoolean(KEY_FULL_SIZE, fullSize)
      .serialize()
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  override fun onAdded() {
    Log.i(TAG, "onAdded() messageId: $messageId  attachmentId: $attachmentId  manual: $manual")

    val attachmentId = AttachmentId(attachmentId)
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId)
    val pending = attachment != null && attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_DONE && attachment.transferState != AttachmentTable.TRANSFER_PROGRESS_PERMANENT_FAILURE
    if (attachment?.transferState == AttachmentTable.TRANSFER_NEEDS_RESTORE) {
      Log.i(TAG, "onAdded() Marking attachment restore progress as 'started'")
      SignalDatabase.attachments.setTransferState(messageId, attachmentId, AttachmentTable.TRANSFER_RESTORE_IN_PROGRESS)
    }
  }

  @Throws(Exception::class)
  public override fun onRun() {
    doWork()

    if (!SignalDatabase.messages.isStory(messageId)) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, forConversation(0))
    }
  }

  @Throws(IOException::class, RetryLaterException::class)
  fun doWork() {
    Log.i(TAG, "onRun() messageId: $messageId  attachmentId: $attachmentId  manual: $manual")

    val attachmentId = AttachmentId(attachmentId)
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId)

    if (attachment == null) {
      Log.w(TAG, "attachment no longer exists.")
      return
    }

    if (attachment.isPermanentlyFailed) {
      Log.w(TAG, "Attachment was marked as a permanent failure. Refusing to download.")
      return
    }

    if (attachment.transferState != AttachmentTable.TRANSFER_NEEDS_RESTORE && attachment.transferState != AttachmentTable.TRANSFER_RESTORE_IN_PROGRESS) {
      Log.w(TAG, "Attachment does not need to be restored.")
      return
    }

    retrieveAttachment(messageId, attachmentId, attachment)
  }

  override fun onFailure() {
    Log.w(TAG, format(this, "onFailure() messageId: $messageId  attachmentId: $attachmentId  manual: $manual"))

    val attachmentId = AttachmentId(attachmentId)
    markFailed(messageId, attachmentId)
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return exception is PushNetworkException ||
      exception is RetryLaterException
  }

  @Throws(IOException::class, RetryLaterException::class)
  private fun retrieveAttachment(
    messageId: Long,
    attachmentId: AttachmentId,
    attachment: DatabaseAttachment
  ) {
    val maxReceiveSize: Long = FeatureFlags.maxAttachmentReceiveSizeBytes()
    val attachmentFile: File = SignalDatabase.attachments.getOrCreateTransferFile(attachmentId)
    var archiveFile: File? = null
    var useArchiveCdn = false

    try {
      if (attachment.size > maxReceiveSize) {
        throw MmsException("Attachment too large, failing download")
      }

      useArchiveCdn = if (SignalStore.backup().canReadWriteToArchiveCdn && (forceArchiveDownload || attachment.remoteLocation == null)) {
        if (attachment.archiveMediaName.isNullOrEmpty()) {
          throw InvalidPartException("Invalid attachment configuration")
        }
        true
      } else {
        false
      }

      val messageReceiver = ApplicationDependencies.getSignalServiceMessageReceiver()
      val pointer = createAttachmentPointer(attachment, useArchiveCdn)

      val progressListener = object : SignalServiceAttachment.ProgressListener {
        override fun onAttachmentProgress(total: Long, progress: Long) {
          EventBus.getDefault().postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress))
        }

        override fun shouldCancel(): Boolean {
          return this@RestoreAttachmentJob.isCanceled
        }
      }

      val stream = if (useArchiveCdn) {
        archiveFile = SignalDatabase.attachments.getOrCreateArchiveTransferFile(attachmentId)
        val cdnCredentials = BackupRepository.getCdnReadCredentials(attachment.archiveCdn).successOrThrow().headers

        messageReceiver
          .retrieveArchivedAttachment(
            SignalStore.svr().getOrCreateMasterKey().deriveBackupKey().deriveMediaSecrets(MediaName(attachment.archiveMediaName!!)),
            cdnCredentials,
            archiveFile,
            pointer,
            attachmentFile,
            maxReceiveSize,
            progressListener
          )
      } else {
        messageReceiver
          .retrieveAttachment(
            pointer,
            attachmentFile,
            maxReceiveSize,
            progressListener
          )
      }

      SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageId, attachmentId, stream)
    } catch (e: RangeException) {
      val transferFile = archiveFile ?: attachmentFile
      Log.w(TAG, "Range exception, file size " + transferFile.length(), e)
      if (transferFile.delete()) {
        Log.i(TAG, "Deleted temp download file to recover")
        throw RetryLaterException(e)
      } else {
        throw IOException("Failed to delete temp download file following range exception")
      }
    } catch (e: InvalidPartException) {
      Log.w(TAG, "Experienced exception while trying to download an attachment.", e)
      markFailed(messageId, attachmentId)
    } catch (e: NonSuccessfulResponseCodeException) {
      if (SignalStore.backup().canReadWriteToArchiveCdn) {
        if (e.code == 404 && !useArchiveCdn && attachment.archiveMediaName?.isNotEmpty() == true) {
          Log.i(TAG, "Retrying download from archive CDN")
          forceArchiveDownload = true
          retrieveAttachment(messageId, attachmentId, attachment)
          return
        } else if (e.code == 401 && useArchiveCdn) {
          SignalStore.backup().cdnReadCredentials = null
          throw RetryLaterException(e)
        }
      }

      Log.w(TAG, "Experienced exception while trying to download an attachment.", e)
      markFailed(messageId, attachmentId)
    } catch (e: MmsException) {
      Log.w(TAG, "Experienced exception while trying to download an attachment.", e)
      markFailed(messageId, attachmentId)
    } catch (e: MissingConfigurationException) {
      Log.w(TAG, "Experienced exception while trying to download an attachment.", e)
      markFailed(messageId, attachmentId)
    } catch (e: InvalidMessageException) {
      Log.w(TAG, "Experienced an InvalidMessageException while trying to download an attachment.", e)
      if (e.cause is InvalidMacException) {
        Log.w(TAG, "Detected an invalid mac. Treating as a permanent failure.")
        markPermanentlyFailed(messageId, attachmentId)
      } else {
        markFailed(messageId, attachmentId)
      }
    }
  }

  @Throws(InvalidPartException::class)
  private fun createAttachmentPointer(attachment: DatabaseAttachment, useArchiveCdn: Boolean): SignalServiceAttachmentPointer {
    if (TextUtils.isEmpty(attachment.remoteKey)) {
      throw InvalidPartException("empty encrypted key")
    }

    return try {
      val remoteData: RemoteData = if (useArchiveCdn) {
        val backupKey = SignalStore.svr().getOrCreateMasterKey().deriveBackupKey()
        val backupDirectories = BackupRepository.getCdnBackupDirectories().successOrThrow()

        RemoteData(
          remoteId = SignalServiceAttachmentRemoteId.Backup(
            backupDir = backupDirectories.backupDir,
            mediaDir = backupDirectories.mediaDir,
            mediaId = backupKey.deriveMediaId(MediaName(attachment.archiveMediaName!!)).encode()
          ),
          cdnNumber = attachment.archiveCdn
        )
      } else {
        if (attachment.remoteLocation.isNullOrEmpty()) {
          throw InvalidPartException("empty content id")
        }

        RemoteData(
          remoteId = SignalServiceAttachmentRemoteId.from(attachment.remoteLocation),
          cdnNumber = attachment.cdn.cdnNumber
        )
      }

      val key = Base64.decode(attachment.remoteKey!!)

      if (attachment.remoteDigest != null) {
        Log.i(TAG, "Downloading attachment with digest: " + Hex.toString(attachment.remoteDigest))
      } else {
        Log.i(TAG, "Downloading attachment with no digest...")
      }

      SignalServiceAttachmentPointer(
        remoteData.cdnNumber,
        remoteData.remoteId,
        null,
        key,
        Optional.of(Util.toIntExact(attachment.size)),
        Optional.empty(),
        0,
        0,
        Optional.ofNullable(attachment.remoteDigest),
        Optional.ofNullable(attachment.getIncrementalDigest()),
        attachment.incrementalMacChunkSize,
        Optional.ofNullable(attachment.fileName),
        attachment.voiceNote,
        attachment.borderless,
        attachment.videoGif,
        Optional.empty(),
        Optional.ofNullable(attachment.blurHash).map { it.hash },
        attachment.uploadTimestamp
      )
    } catch (e: IOException) {
      Log.w(TAG, e)
      throw InvalidPartException(e)
    } catch (e: ArithmeticException) {
      Log.w(TAG, e)
      throw InvalidPartException(e)
    }
  }

  private fun markFailed(messageId: Long, attachmentId: AttachmentId) {
    try {
      SignalDatabase.attachments.setTransferProgressFailed(attachmentId, messageId)
    } catch (e: MmsException) {
      Log.w(TAG, e)
    }
  }

  private fun markPermanentlyFailed(messageId: Long, attachmentId: AttachmentId) {
    try {
      SignalDatabase.attachments.setTransferProgressPermanentFailure(attachmentId, messageId)
    } catch (e: MmsException) {
      Log.w(TAG, e)
    }
  }

  @VisibleForTesting
  internal class InvalidPartException : Exception {
    constructor(s: String?) : super(s)
    constructor(e: Exception?) : super(e)
  }

  private data class RemoteData(val remoteId: SignalServiceAttachmentRemoteId, val cdnNumber: Int)

  class Factory : Job.Factory<RestoreAttachmentJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RestoreAttachmentJob {
      val data = JsonJobData.deserialize(serializedData)
      return RestoreAttachmentJob(
        parameters = parameters,
        messageId = data.getLong(KEY_MESSAGE_ID),
        attachmentId = AttachmentId(data.getLong(KEY_ATTACHMENT_ID)),
        manual = data.getBoolean(KEY_MANUAL),
        forceArchiveDownload = data.getBooleanOrDefault(KEY_FORCE_ARCHIVE, false),
        fullSize = data.getBooleanOrDefault(KEY_FULL_SIZE, true)
      )
    }
  }
}
