/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.util.Base64
import org.signal.core.util.SqlUtil
import org.signal.core.util.logging.Log
import org.signal.core.util.readFully
import org.signal.libsignal.messagebackup.ComparableBackup
import org.signal.libsignal.messagebackup.MessageBackup
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.backup.v2.proto.Frame
import org.thoughtcrime.securesms.backup.v2.stream.PlainTextBackupReader
import org.thoughtcrime.securesms.database.DistributionListTables
import org.thoughtcrime.securesms.database.KeyValueDatabase
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.kbs.MasterKey
import org.whispersystems.signalservice.api.push.ServiceId
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveImportExportTests {

  companion object {
    const val TAG = "ImportExport"
    const val TESTS_FOLDER = "backupTests"

    val SELF_ACI = ServiceId.ACI.from(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    val SELF_PNI = ServiceId.PNI.from(UUID.fromString("00000000-0000-4000-8000-000000000002"))
    val SELF_E164 = "+10000000000"
    val SELF_PROFILE_KEY: ByteArray = Base64.decode("YQKRq+3DQklInaOaMcmlzZnN0m/1hzLiaONX7gB12dg=")
    val MASTER_KEY = Base64.decode("sHuBMP4ToZk4tcNU+S8eBUeCt8Am5EZnvuqTBJIR4Do")
  }

//  @Test
  fun all() {
    runTests()
  }

  @Test
  fun temp() {
    runTests { it == "chat_item_standard_message_formatted_text_03.binproto" }
  }

  // Passing
//  @Test
  fun accountData() {
    runTests { it.startsWith("account_data_") }
  }

  @Test
  fun adHocCall() {
    runTests { it.startsWith("ad_hoc_call") }
  }

  // Passing
//  @Test
  fun chat() {
    runTests { it.startsWith("chat_") && !it.contains("_item") }
  }

  // Passing
//  @Test
  fun chatItemContactMessage() {
    runTests { it.startsWith("chat_item_contact_message_") }
  }

  // Passing
//  @Test
  fun chatItemExpirationTimerUpdate() {
    runTests { it.startsWith("chat_item_expiration_timer_") }
  }

  // Passing
//  @Test
  fun chatItemGiftBadge() {
    runTests { it.startsWith("chat_item_gift_badge_") }
  }

  @Test
  fun chatItemGroupCallUpdate() {
    runTests { it.startsWith("chat_item_group_call_update_") }
  }

  @Test
  fun chatItemIndividualCallUpdate() {
    runTests { it.startsWith("chat_item_individual_call_update_") }
  }

  // Passing
//  @Test
  fun chatItemLearnedProfileUpdate() {
    runTests { it.startsWith("chat_item_learned_profile_update_") }
  }

  @Test
  fun chatItemPaymentNotification() {
    runTests { it.startsWith("chat_item_payment_notification_") }
  }

  // Passing
//  @Test
  fun chatItemProfileChangeUpdate() {
    runTests { it.startsWith("chat_item_profile_change_update_") }
  }

  // Passing
//  @Test
  fun chatItemRemoteDelete() {
    runTests { it.startsWith("chat_item_remote_delete_") }
  }

  // Passing
//  @Test
  fun chatItemSessionSwitchoverUpdate() {
    runTests { it.startsWith("chat_item_session_switchover_update_") }
  }

  @Test
  fun chatItemSimpleUpdates() {
    runTests { it.startsWith("chat_item_simple_updates_") }
  }

  // Passing
//  @Test
  fun chatItemStandardMessageFormattedText() {
    runTests { it.startsWith("chat_item_standard_message_formatted_text_") }
  }

  @Test
  fun chatItemStandardMessageLongText() {
    runTests { it.startsWith("chat_item_standard_message_long_text_") }
  }

  // Passing
//  @Test
  fun chatItemStandardMessageSpecialAttachments() {
    runTests { it.startsWith("chat_item_standard_message_special_attachments_") }
  }

  // Passing
//  @Test
  fun chatItemStandardMessageStandardAttachments() {
    runTests { it.startsWith("chat_item_standard_message_standard_attachments_") }
  }

  // Passing
//  @Test
  fun chatItemStandardMessageTextOnly() {
    runTests { it.startsWith("chat_item_standard_message_text_only_") }
  }

  @Test
  fun chatItemStandardMessageWithEdits() {
    runTests { it.startsWith("chat_item_standard_message_with_edits_") }
  }

  @Test
  fun chatItemStandardMessageWithQuote() {
    runTests { it.startsWith("chat_item_standard_message_with_quote_") }
  }

  @Test
  fun chatItemStickerMessage() {
    runTests { it.startsWith("chat_item_sticker_message_") }
  }

  // Passing
//  @Test
  fun chatItemThreadMergeUpdate() {
    runTests { it.startsWith("chat_item_thread_merge_update_") }
  }

  @Test
  fun recipientCallLink() {
    runTests { it.startsWith("recipient_call_link_") }
  }

  // Passing
//  @Test
  fun recipientContacts() {
    runTests { it.startsWith("recipient_contacts_") }
  }

  // Passing
//  @Test
  fun recipientDistributionLists() {
    runTests { it.startsWith("recipient_distribution_list_") }
  }

  // Passing
//  @Test
  fun recipientGroups() {
    runTests { it.startsWith("recipient_groups_") }
  }

  private fun runTests(predicate: (String) -> Boolean = { true }) {
    val testFiles = InstrumentationRegistry.getInstrumentation().context.resources.assets.list(TESTS_FOLDER)!!.filter(predicate)
    val results: MutableList<TestResult> = mutableListOf()

    Log.d(TAG, "About to run ${testFiles.size} tests.")

    for (filename in testFiles) {
      Log.d(TAG, "> $filename")
      val startTime = System.currentTimeMillis()
      val result = test(filename)
      results += result

      if (result is TestResult.Success) {
        Log.d(TAG, "  \uD83D\uDFE2 Passed in ${System.currentTimeMillis() - startTime} ms")
      } else {
        Log.d(TAG, "  \uD83D\uDD34 Failed in ${System.currentTimeMillis() - startTime} ms")
      }
    }

    results
      .filterIsInstance<TestResult.Failure>()
      .forEach {
        Log.e(TAG, "Failure: ${it.name}\n${it.message}")
        Log.e(TAG, "----------------------------------")
        Log.e(TAG, "----------------------------------")
        Log.e(TAG, "----------------------------------")
      }

    if (results.any { it is TestResult.Failure }) {
      val successCount = results.count { it is TestResult.Success }
      val failingTestNames = results.filterIsInstance<TestResult.Failure>().joinToString(separator = "\n") { "  \uD83D\uDD34 ${it.name}" }
      val message = "Some tests failed! Only $successCount/${results.size} passed. Failure details are above. Failing tests:\n$failingTestNames"

      Log.d(TAG, message)
      throw AssertionError("Some tests failed!")
    } else {
      Log.d(TAG, "All ${results.size} tests passed!")
    }
  }

  private fun test(filename: String): TestResult {
    resetAllData()

    val inputFileBytes: ByteArray = InstrumentationRegistry.getInstrumentation().context.resources.assets.open("$TESTS_FOLDER/$filename").readFully(true)

    val importResult = import(inputFileBytes)
    assertTrue(importResult is ImportResult.Success)
    val success = importResult as ImportResult.Success

    val generatedBackupData = BackupRepository.debugExport(plaintext = true, currentTime = success.backupTime)
    checkEquivalent(filename, inputFileBytes, generatedBackupData)?.let { return it }

    return TestResult.Success(filename)
  }

  private fun resetAllData() {
    // Need to delete these first to prevent foreign key crash
    SignalDatabase.rawDatabase.execSQL("DELETE FROM ${DistributionListTables.ListTable.TABLE_NAME}")
    SignalDatabase.rawDatabase.execSQL("DELETE FROM ${DistributionListTables.MembershipTable.TABLE_NAME}")

    SqlUtil.getAllTables(SignalDatabase.rawDatabase)
      .filterNot { it.contains("sqlite") || it.contains("fts") || it.startsWith("emoji_search_") } // If we delete these we'll corrupt the DB
      .sorted()
      .forEach { table ->
        SignalDatabase.rawDatabase.execSQL("DELETE FROM $table")
        SqlUtil.resetAutoIncrementValue(SignalDatabase.rawDatabase, table)
      }

    AppDependencies.recipientCache.clear()
    AppDependencies.recipientCache.clearSelf()
    RecipientId.clearCache()

    KeyValueDatabase.getInstance(AppDependencies.application).clear()
    SignalStore.resetCache()

    SignalStore.svr.setMasterKey(MasterKey(MASTER_KEY), "1234")
    SignalStore.account.setE164(SELF_E164)
    SignalStore.account.setAci(SELF_ACI)
    SignalStore.account.setPni(SELF_PNI)
    SignalStore.account.generateAciIdentityKeyIfNecessary()
    SignalStore.account.generatePniIdentityKeyIfNecessary()
    SignalStore.backup.backupTier = MessageBackupTier.PAID
  }

  private fun import(importData: ByteArray): ImportResult {
    return BackupRepository.import(
      length = importData.size.toLong(),
      inputStreamFactory = { ByteArrayInputStream(importData) },
      selfData = BackupRepository.SelfData(SELF_ACI, SELF_PNI, SELF_E164, ProfileKey(SELF_PROFILE_KEY)),
      plaintext = true
    )
  }

  private fun assertPassesValidator(testName: String, generatedBackupData: ByteArray): TestResult.Failure? {
    try {
      BackupRepository.validate(
        length = generatedBackupData.size.toLong(),
        inputStreamFactory = { ByteArrayInputStream(generatedBackupData) },
        selfData = BackupRepository.SelfData(SELF_ACI, SELF_PNI, SELF_E164, ProfileKey(SELF_PROFILE_KEY))
      )
    } catch (e: Exception) {
      return TestResult.Failure(testName, "Generated backup failed validation: ${e.message}")
    }

    return null
  }

  private fun checkEquivalent(testName: String, import: ByteArray, export: ByteArray): TestResult.Failure? {
    val importComparable = try {
      ComparableBackup.readUnencrypted(MessageBackup.Purpose.REMOTE_BACKUP, import.inputStream(), import.size.toLong())
    } catch (e: Exception) {
      return TestResult.Failure(testName, "Imported backup hit a validation error: ${e.message}")
    }

    val exportComparable = try {
      ComparableBackup.readUnencrypted(MessageBackup.Purpose.REMOTE_BACKUP, export.inputStream(), import.size.toLong())
    } catch (e: Exception) {
      return TestResult.Failure(testName, "Exported backup hit a validation error: ${e.message}")
    }

    if (importComparable.unknownFieldMessages.isNotEmpty()) {
      return TestResult.Failure(testName, "Imported backup contains unknown fields: ${importComparable.unknownFieldMessages}")
    }

    if (exportComparable.unknownFieldMessages.isNotEmpty()) {
      return TestResult.Failure(testName, "Imported backup contains unknown fields: ${importComparable.unknownFieldMessages}")
    }

    val canonicalImport = importComparable.comparableString
    val canonicalExport = exportComparable.comparableString

    if (canonicalImport != canonicalExport) {
      val importLines = canonicalImport.lines()
      val exportLines = canonicalExport.lines()

      val patch = DiffUtils.diff(importLines, exportLines)
      val diff = UnifiedDiffUtils.generateUnifiedDiff("Import", "Export", importLines, patch, 3).joinToString(separator = "\n")

      val importFrames = import.toFrames()
      val exportFrames = export.toFrames()

      val importGroupFramesByMasterKey = importFrames.mapNotNull { it.recipient?.group }.associateBy { it.masterKey }
      val exportGroupFramesByMasterKey = exportFrames.mapNotNull { it.recipient?.group }.associateBy { it.masterKey }

      val groupErrorMessage = StringBuilder()

      for ((importKey, importValue) in importGroupFramesByMasterKey) {
        if (exportGroupFramesByMasterKey[importKey]?.let { it.snapshot != importValue.snapshot } == true) {
          groupErrorMessage.append("[$importKey] Snapshot mismatch.\nImport:\n${importValue}\n\nExport:\n${exportGroupFramesByMasterKey[importKey]}\n\n")
        }
      }

      return TestResult.Failure(testName, "Imported backup does not match exported backup. Diff:\n$diff\n$groupErrorMessage")
    }

    return null
  }

  fun ByteArray.toFrames(): List<Frame> {
    return PlainTextBackupReader(this.inputStream(), this.size.toLong()).use { it.asSequence().toList() }
  }

  private sealed class TestResult(val name: String) {
    class Success(name: String) : TestResult(name)
    class Failure(name: String, val message: String) : TestResult(name)
  }
}
