/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.database

import android.content.ContentValues
import android.database.Cursor
import okio.ByteString.Companion.toByteString
import org.signal.core.util.Base64
import org.signal.core.util.SqlUtil
import org.signal.core.util.delete
import org.signal.core.util.logging.Log
import org.signal.core.util.nullIfBlank
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullBlob
import org.signal.core.util.requireString
import org.signal.core.util.select
import org.signal.core.util.toInt
import org.signal.core.util.update
import org.signal.libsignal.zkgroup.InvalidInputException
import org.thoughtcrime.securesms.backup.v2.BackupState
import org.thoughtcrime.securesms.backup.v2.proto.AccountData
import org.thoughtcrime.securesms.backup.v2.proto.Contact
import org.thoughtcrime.securesms.backup.v2.proto.Group
import org.thoughtcrime.securesms.backup.v2.proto.Self
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.RecipientTableCursorUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.RecipientExtras
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import java.io.Closeable

typealias BackupRecipient = org.thoughtcrime.securesms.backup.v2.proto.Recipient
typealias BackupGroup = Group

/**
 * Fetches all individual contacts for backups and returns the result as an iterator.
 * It's important to note that the iterator still needs to be closed after it's used.
 * It's recommended to use `.use` or a try-with-resources pattern.
 */
fun RecipientTable.getContactsForBackup(selfId: Long): BackupContactIterator {
  val cursor = readableDatabase
    .select(
      RecipientTable.ID,
      RecipientTable.ACI_COLUMN,
      RecipientTable.PNI_COLUMN,
      RecipientTable.USERNAME,
      RecipientTable.E164,
      RecipientTable.BLOCKED,
      RecipientTable.HIDDEN,
      RecipientTable.REGISTERED,
      RecipientTable.UNREGISTERED_TIMESTAMP,
      RecipientTable.PROFILE_KEY,
      RecipientTable.PROFILE_SHARING,
      RecipientTable.PROFILE_GIVEN_NAME,
      RecipientTable.PROFILE_FAMILY_NAME,
      RecipientTable.PROFILE_JOINED_NAME,
      RecipientTable.MUTE_UNTIL,
      RecipientTable.EXTRAS
    )
    .from(RecipientTable.TABLE_NAME)
    .where(
      """
      ${RecipientTable.TYPE} = ? AND (
        ${RecipientTable.ACI_COLUMN} NOT NULL OR
        ${RecipientTable.PNI_COLUMN} NOT NULL OR
        ${RecipientTable.E164} NOT NULL
      )
      """,
      RecipientTable.RecipientType.INDIVIDUAL.id
    )
    .run()

  return BackupContactIterator(cursor, selfId)
}

fun RecipientTable.getGroupsForBackup(): BackupGroupIterator {
  val cursor = readableDatabase
    .select(
      "${RecipientTable.TABLE_NAME}.${RecipientTable.ID}",
      "${RecipientTable.TABLE_NAME}.${RecipientTable.BLOCKED}",
      "${RecipientTable.TABLE_NAME}.${RecipientTable.PROFILE_SHARING}",
      "${RecipientTable.TABLE_NAME}.${RecipientTable.MUTE_UNTIL}",
      "${RecipientTable.TABLE_NAME}.${RecipientTable.EXTRAS}",
      "${GroupTable.TABLE_NAME}.${GroupTable.V2_MASTER_KEY}",
      "${GroupTable.TABLE_NAME}.${GroupTable.SHOW_AS_STORY_STATE}"
    )
    .from(
      """
      ${RecipientTable.TABLE_NAME} 
        INNER JOIN ${GroupTable.TABLE_NAME} ON ${RecipientTable.TABLE_NAME}.${RecipientTable.ID} = ${GroupTable.TABLE_NAME}.${GroupTable.RECIPIENT_ID}
      """
    )
    .run()

  return BackupGroupIterator(cursor)
}

/**
 * Takes a [BackupRecipient] and writes it into the database.
 */
fun RecipientTable.restoreRecipientFromBackup(recipient: BackupRecipient, backupState: BackupState): RecipientId? {
  // TODO Need to handle groups
  // TODO Also, should we move this when statement up to mimic the export? Kinda weird that this calls distributionListTable functions
  return when {
    recipient.contact != null -> restoreContactFromBackup(recipient.contact)
    recipient.distributionList != null -> SignalDatabase.distributionLists.restoreFromBackup(recipient.distributionList, backupState)
    recipient.self != null -> Recipient.self().id
    else -> {
      Log.w(TAG, "Unrecognized recipient type!")
      null
    }
  }
}

/**
 * Given [AccountData], this will insert the necessary data for the local user into the [RecipientTable].
 */
fun RecipientTable.restoreSelfFromBackup(accountData: AccountData, selfId: RecipientId) {
  val values = ContentValues().apply {
    put(RecipientTable.PROFILE_GIVEN_NAME, accountData.givenName.nullIfBlank())
    put(RecipientTable.PROFILE_FAMILY_NAME, accountData.familyName.nullIfBlank())
    put(RecipientTable.PROFILE_JOINED_NAME, ProfileName.fromParts(accountData.givenName, accountData.familyName).toString().nullIfBlank())
    put(RecipientTable.PROFILE_AVATAR, accountData.avatarUrlPath.nullIfBlank())
    put(RecipientTable.REGISTERED, RecipientTable.RegisteredState.REGISTERED.id)
    put(RecipientTable.PROFILE_SHARING, true)
    put(RecipientTable.UNREGISTERED_TIMESTAMP, 0)
    put(RecipientTable.EXTRAS, RecipientExtras().encode())

    try {
      put(RecipientTable.PROFILE_KEY, Base64.encodeWithPadding(accountData.profileKey.toByteArray()).nullIfBlank())
    } catch (e: InvalidInputException) {
      Log.w(TAG, "Missing profile key during restore")
    }

    put(RecipientTable.USERNAME, accountData.username)
  }

  writableDatabase
    .update(RecipientTable.TABLE_NAME)
    .values(values)
    .where("${RecipientTable.ID} = ?", selfId)
    .run()
}

fun RecipientTable.clearAllDataForBackupRestore() {
  writableDatabase.delete(RecipientTable.TABLE_NAME).run()
  SqlUtil.resetAutoIncrementValue(writableDatabase, RecipientTable.TABLE_NAME)

  RecipientId.clearCache()
  ApplicationDependencies.getRecipientCache().clear()
  ApplicationDependencies.getRecipientCache().clearSelf()
}

private fun RecipientTable.restoreContactFromBackup(contact: Contact): RecipientId {
  val id = getAndPossiblyMergePnpVerified(
    aci = ACI.parseOrNull(contact.aci?.toByteArray()),
    pni = PNI.parseOrNull(contact.pni?.toByteArray()),
    e164 = contact.formattedE164
  )

  val profileKey = contact.profileKey?.toByteArray()

  writableDatabase
    .update(RecipientTable.TABLE_NAME)
    .values(
      RecipientTable.BLOCKED to contact.blocked,
      RecipientTable.HIDDEN to contact.hidden,
      RecipientTable.PROFILE_FAMILY_NAME to contact.profileFamilyName.nullIfBlank(),
      RecipientTable.PROFILE_GIVEN_NAME to contact.profileGivenName.nullIfBlank(),
      RecipientTable.PROFILE_JOINED_NAME to ProfileName.fromParts(contact.profileGivenName.nullIfBlank(), contact.profileFamilyName.nullIfBlank()).toString().nullIfBlank(),
      RecipientTable.PROFILE_KEY to if (profileKey == null) null else Base64.encodeWithPadding(profileKey),
      RecipientTable.PROFILE_SHARING to contact.profileSharing.toInt(),
      RecipientTable.REGISTERED to contact.registered.toLocalRegisteredState().id,
      RecipientTable.USERNAME to contact.username,
      RecipientTable.UNREGISTERED_TIMESTAMP to contact.unregisteredTimestamp,
      RecipientTable.EXTRAS to contact.toLocalExtras().encode()
    )
    .where("${RecipientTable.ID} = ?", id)
    .run()

  return id
}

private fun Contact.toLocalExtras(): RecipientExtras {
  return RecipientExtras(
    hideStory = this.hideStory
  )
}

/**
 * Provides a nice iterable interface over a [RecipientTable] cursor, converting rows to [BackupRecipient]s.
 * Important: Because this is backed by a cursor, you must close it. It's recommended to use `.use()` or try-with-resources.
 */
class BackupContactIterator(private val cursor: Cursor, private val selfId: Long) : Iterator<BackupRecipient?>, Closeable {
  override fun hasNext(): Boolean {
    return cursor.count > 0 && !cursor.isLast
  }

  override fun next(): BackupRecipient? {
    if (!cursor.moveToNext()) {
      throw NoSuchElementException()
    }

    val id = cursor.requireLong(RecipientTable.ID)
    if (id == selfId) {
      return BackupRecipient(
        id = id,
        self = Self()
      )
    }

    val aci = ACI.parseOrNull(cursor.requireString(RecipientTable.ACI_COLUMN))
    val pni = PNI.parseOrNull(cursor.requireString(RecipientTable.PNI_COLUMN))
    val e164 = cursor.requireString(RecipientTable.E164)?.e164ToLong()
    val registeredState = RecipientTable.RegisteredState.fromId(cursor.requireInt(RecipientTable.REGISTERED))
    val profileKey = cursor.requireString(RecipientTable.PROFILE_KEY)
    val extras = RecipientTableCursorUtil.getExtras(cursor)

    if (aci == null && pni == null && e164 == null) {
      return null
    }

    return BackupRecipient(
      id = id,
      contact = Contact(
        aci = aci?.toByteArray()?.toByteString(),
        pni = pni?.toByteArray()?.toByteString(),
        username = cursor.requireString(RecipientTable.USERNAME),
        e164 = cursor.requireString(RecipientTable.E164)?.e164ToLong(),
        blocked = cursor.requireBoolean(RecipientTable.BLOCKED),
        hidden = cursor.requireBoolean(RecipientTable.HIDDEN),
        registered = registeredState.toContactRegisteredState(),
        unregisteredTimestamp = cursor.requireLong(RecipientTable.UNREGISTERED_TIMESTAMP),
        profileKey = if (profileKey != null) Base64.decode(profileKey).toByteString() else null,
        profileSharing = cursor.requireBoolean(RecipientTable.PROFILE_SHARING),
        profileGivenName = cursor.requireString(RecipientTable.PROFILE_GIVEN_NAME).nullIfBlank(),
        profileFamilyName = cursor.requireString(RecipientTable.PROFILE_FAMILY_NAME).nullIfBlank(),
        hideStory = extras?.hideStory() ?: false
      )
    )
  }

  override fun close() {
    cursor.close()
  }
}

/**
 * Provides a nice iterable interface over a [RecipientTable] cursor, converting rows to [BackupRecipient]s.
 * Important: Because this is backed by a cursor, you must close it. It's recommended to use `.use()` or try-with-resources.
 */
class BackupGroupIterator(private val cursor: Cursor) : Iterator<BackupRecipient>, Closeable {
  override fun hasNext(): Boolean {
    return cursor.count > 0 && !cursor.isLast
  }

  override fun next(): BackupRecipient {
    if (!cursor.moveToNext()) {
      throw NoSuchElementException()
    }

    val extras = RecipientTableCursorUtil.getExtras(cursor)
    val showAsStoryState: GroupTable.ShowAsStoryState = GroupTable.ShowAsStoryState.deserialize(cursor.requireInt(GroupTable.SHOW_AS_STORY_STATE))

    return BackupRecipient(
      id = cursor.requireLong(RecipientTable.ID),
      group = BackupGroup(
        masterKey = cursor.requireNonNullBlob(GroupTable.V2_MASTER_KEY).toByteString(),
        whitelisted = cursor.requireBoolean(RecipientTable.PROFILE_SHARING),
        hideStory = extras?.hideStory() ?: false,
        storySendMode = showAsStoryState.toGroupStorySendMode()
      )
    )
  }

  override fun close() {
    cursor.close()
  }
}

private fun String.e164ToLong(): Long? {
  val fixed = if (this.startsWith("+")) {
    this.substring(1)
  } else {
    this
  }

  return fixed.toLongOrNull()
}

private fun RecipientTable.RegisteredState.toContactRegisteredState(): Contact.Registered {
  return when (this) {
    RecipientTable.RegisteredState.REGISTERED -> Contact.Registered.REGISTERED
    RecipientTable.RegisteredState.NOT_REGISTERED -> Contact.Registered.NOT_REGISTERED
    RecipientTable.RegisteredState.UNKNOWN -> Contact.Registered.UNKNOWN
  }
}

private fun Contact.Registered.toLocalRegisteredState(): RecipientTable.RegisteredState {
  return when (this) {
    Contact.Registered.REGISTERED -> RecipientTable.RegisteredState.REGISTERED
    Contact.Registered.NOT_REGISTERED -> RecipientTable.RegisteredState.NOT_REGISTERED
    Contact.Registered.UNKNOWN -> RecipientTable.RegisteredState.UNKNOWN
  }
}

private fun GroupTable.ShowAsStoryState.toGroupStorySendMode(): Group.StorySendMode {
  return when (this) {
    GroupTable.ShowAsStoryState.ALWAYS -> Group.StorySendMode.ENABLED
    GroupTable.ShowAsStoryState.NEVER -> Group.StorySendMode.DISABLED
    GroupTable.ShowAsStoryState.IF_ACTIVE -> Group.StorySendMode.DEFAULT
  }
}

private val Contact.formattedE164: String?
  get() {
    return e164?.let {
      PhoneNumberFormatter.get(ApplicationDependencies.getApplication()).format(e164.toString())
    }
  }
