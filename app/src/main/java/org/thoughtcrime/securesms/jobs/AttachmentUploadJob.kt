/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.graphics.Bitmap
import android.os.Build
import android.text.TextUtils
import org.greenrobot.eventbus.EventBus
import org.signal.core.util.inRoundedDays
import org.signal.core.util.logging.Log
import org.signal.core.util.mebiBytes
import org.signal.protos.resumableuploads.ResumableUpload
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.blurhash.BlurHashEncoder
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.events.PartProgressEvent
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.jobs.protos.AttachmentUploadJobData
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.net.NotPushRegisteredException
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.service.AttachmentProgressService
import org.thoughtcrime.securesms.util.FeatureFlags
import org.thoughtcrime.securesms.util.MediaUtil
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResumableUploadResponseCodeException
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec
import java.io.IOException
import java.util.Objects
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Uploads an attachment without alteration.
 *
 * Queue [AttachmentCompressionJob] before to compress.
 */
class AttachmentUploadJob private constructor(
  parameters: Parameters,
  private val attachmentId: AttachmentId,
  private var uploadSpec: ResumableUpload?
) : BaseJob(parameters) {

  companion object {
    const val KEY = "AttachmentUploadJobV3"

    private val TAG = Log.tag(AttachmentUploadJob::class.java)

    private val UPLOAD_REUSE_THRESHOLD = TimeUnit.DAYS.toMillis(3)

    /**
     * Foreground notification shows while uploading attachments above this.
     */
    private val FOREGROUND_LIMIT = 10.mebiBytes.inWholeBytes

    @JvmStatic
    val maxPlaintextSize: Long
      get() {
        val maxCipherTextSize = FeatureFlags.maxAttachmentSizeBytes()
        val maxPaddedSize = AttachmentCipherStreamUtil.getPlaintextLength(maxCipherTextSize)
        return PaddingInputStream.getMaxUnpaddedSize(maxPaddedSize)
      }
  }

  constructor(attachmentId: AttachmentId) : this(
    Parameters.Builder()
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build(),
    attachmentId,
    null
  )

  override fun serialize(): ByteArray {
    return AttachmentUploadJobData(
      attachmentRowId = attachmentId.rowId,
      attachmentUniqueId = attachmentId.uniqueId,
      uploadSpec = uploadSpec
    ).encode()
  }

  override fun getFactoryKey(): String = KEY

  override fun shouldTrace(): Boolean = true

  @Throws(Exception::class)
  public override fun onRun() {
    if (!Recipient.self().isRegistered) {
      throw NotPushRegisteredException()
    }

    val messageSender = ApplicationDependencies.getSignalServiceMessageSender()
    val databaseAttachment = SignalDatabase.attachments.getAttachment(attachmentId) ?: throw InvalidAttachmentException("Cannot find the specified attachment.")

    val timeSinceUpload = System.currentTimeMillis() - databaseAttachment.uploadTimestamp
    if (timeSinceUpload < UPLOAD_REUSE_THRESHOLD && !TextUtils.isEmpty(databaseAttachment.location)) {
      Log.i(TAG, "We can re-use an already-uploaded file. It was uploaded $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days) ago. Skipping.")
      return
    } else if (databaseAttachment.uploadTimestamp > 0) {
      Log.i(TAG, "This file was previously-uploaded, but too long ago to be re-used. Age: $timeSinceUpload ms (${timeSinceUpload.milliseconds.inRoundedDays()} days)")
    }

    if (uploadSpec != null && System.currentTimeMillis() > uploadSpec!!.timeout) {
      Log.w(TAG, "Upload spec expired! Clearing.")
      uploadSpec = null
    }

    if (uploadSpec == null) {
      Log.d(TAG, "Need an upload spec. Fetching...")
      uploadSpec = ApplicationDependencies.getSignalServiceMessageSender().getResumableUploadSpec().toProto()
    } else {
      Log.d(TAG, "Re-using existing upload spec.")
    }

    Log.i(TAG, "Uploading attachment for message " + databaseAttachment.mmsId + " with ID " + databaseAttachment.attachmentId)
    try {
      getAttachmentNotificationIfNeeded(databaseAttachment).use { notification ->
        buildAttachmentStream(databaseAttachment, notification, uploadSpec!!).use { localAttachment ->
          val remoteAttachment = messageSender.uploadAttachment(localAttachment)
          val attachment = PointerAttachment.forPointer(Optional.of(remoteAttachment), null, databaseAttachment.fastPreflightId).get()
          SignalDatabase.attachments.updateAttachmentAfterUpload(databaseAttachment.attachmentId, attachment, remoteAttachment.uploadTimestamp)
        }
      }
    } catch (e: NonSuccessfulResumableUploadResponseCodeException) {
      if (e.code == 400) {
        Log.w(TAG, "Failed to upload due to a 400 when getting resumable upload information. Clearing upload spec.", e)
        uploadSpec = null
      }

      throw e
    }
  }

  private fun getAttachmentNotificationIfNeeded(attachment: Attachment): AttachmentProgressService.Controller? {
    return if (attachment.size >= FOREGROUND_LIMIT) {
      try {
        AttachmentProgressService.start(context, context.getString(R.string.AttachmentUploadJob_uploading_media))
      } catch (e: UnableToStartException) {
        Log.w(TAG, "Unable to start foreground service", e)
        null
      }
    } else {
      null
    }
  }

  override fun onFailure() {
    if (isCanceled) {
      SignalDatabase.attachments.deleteAttachment(attachmentId)
    }
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return exception is IOException && exception !is NotPushRegisteredException
  }

  @Throws(InvalidAttachmentException::class)
  private fun buildAttachmentStream(attachment: Attachment, notification: AttachmentProgressService.Controller?, resumableUploadSpec: ResumableUpload): SignalServiceAttachmentStream {
    if (attachment.uri == null || attachment.size == 0L) {
      throw InvalidAttachmentException(IOException("Outgoing attachment has no data!"))
    }

    return try {
      val inputStream = PartAuthority.getAttachmentStream(context, attachment.uri!!)
      val builder = SignalServiceAttachment.newStreamBuilder()
        .withStream(inputStream)
        .withContentType(attachment.contentType)
        .withLength(attachment.size)
        .withFileName(attachment.fileName)
        .withVoiceNote(attachment.isVoiceNote)
        .withBorderless(attachment.isBorderless)
        .withGif(attachment.isVideoGif)
        .withWidth(attachment.width)
        .withHeight(attachment.height)
        .withUploadTimestamp(System.currentTimeMillis())
        .withCaption(attachment.caption)
        .withResumableUploadSpec(ResumableUploadSpec.from(resumableUploadSpec))
        .withCancelationSignal { this.isCanceled }
        .withListener(object : SignalServiceAttachment.ProgressListener {
          override fun onAttachmentProgress(total: Long, progress: Long) {
            EventBus.getDefault().postSticky(PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress))
            notification?.progress = (progress.toFloat() / total)
          }

          override fun shouldCancel(): Boolean {
            return isCanceled
          }
        })

      if (MediaUtil.isImageType(attachment.contentType)) {
        builder.withBlurHash(getImageBlurHash(attachment)).build()
      } else if (MediaUtil.isVideoType(attachment.contentType)) {
        builder.withBlurHash(getVideoBlurHash(attachment)).build()
      } else {
        builder.build()
      }
    } catch (e: IOException) {
      throw InvalidAttachmentException(e)
    }
  }

  @Throws(IOException::class)
  private fun getImageBlurHash(attachment: Attachment): String? {
    if (attachment.blurHash != null) {
      return attachment.blurHash!!.hash
    }

    if (attachment.uri == null) {
      return null
    }

    return PartAuthority.getAttachmentStream(context, attachment.uri!!).use { inputStream ->
      BlurHashEncoder.encode(inputStream)
    }
  }

  @Throws(IOException::class)
  private fun getVideoBlurHash(attachment: Attachment): String? {
    if (attachment.blurHash != null) {
      return attachment.blurHash!!.hash
    }

    if (Build.VERSION.SDK_INT < 23) {
      Log.w(TAG, "Video thumbnails not supported...")
      return null
    }

    return MediaUtil.getVideoThumbnail(context, Objects.requireNonNull(attachment.uri), 1000)?.let { bitmap ->
      val thumb = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
      bitmap.recycle()

      Log.i(TAG, "Generated video thumbnail...")
      val hash = BlurHashEncoder.encode(thumb)
      thumb.recycle()

      hash
    }
  }

  private inner class InvalidAttachmentException : Exception {
    constructor(message: String?) : super(message)
    constructor(e: Exception?) : super(e)
  }

  class Factory : Job.Factory<AttachmentUploadJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): AttachmentUploadJob {
      val data = AttachmentUploadJobData.ADAPTER.decode(serializedData!!)
      return AttachmentUploadJob(
        parameters = parameters,
        attachmentId = AttachmentId(data.attachmentRowId, data.attachmentUniqueId),
        data.uploadSpec
      )
    }
  }
}
