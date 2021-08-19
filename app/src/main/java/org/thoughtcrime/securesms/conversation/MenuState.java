package org.thoughtcrime.securesms.conversation;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.FeatureFlags;

import java.util.Set;
import java.util.stream.Collectors;

final class MenuState {

  private static final int MAX_FORWARDABLE_COUNT = 32;

  private final boolean forward;
  private final boolean reply;
  private final boolean details;
  private final boolean saveAttachment;
  private final boolean resend;
  private final boolean copy;
  private final boolean delete;

  private MenuState(@NonNull Builder builder) {
    forward        = builder.forward;
    reply          = builder.reply;
    details        = builder.details;
    saveAttachment = builder.saveAttachment;
    resend         = builder.resend;
    copy           = builder.copy;
    delete         = builder.delete;
  }

  boolean shouldShowForwardAction() {
    return forward;
  }

  boolean shouldShowReplyAction() {
    return reply;
  }

  boolean shouldShowDetailsAction() {
    return details;
  }

  boolean shouldShowSaveAttachmentAction() {
    return saveAttachment;
  }

  boolean shouldShowResendAction() {
    return resend;
  }

  boolean shouldShowCopyAction() {
    return copy;
  }

  boolean shouldShowDeleteAction() {
    return delete;
  }

  static MenuState getMenuState(@NonNull Recipient conversationRecipient,
                                @NonNull Set<MultiselectPart> selectedParts,
                                boolean shouldShowMessageRequest)
  {
    
    Builder builder         = new Builder();
    boolean actionMessage   = false;
    boolean hasText         = false;
    boolean sharedContact   = false;
    boolean viewOnce        = false;
    boolean remoteDelete    = false;
    boolean hasInMemory     = false;
    boolean hasPendingMedia = false;
    boolean mediaIsSelected = false;

    for (MultiselectPart part : selectedParts) {
      MessageRecord messageRecord = part.getMessageRecord();

      if (isActionMessage(messageRecord)) {
        actionMessage = true;
        if (messageRecord.isInMemoryMessageRecord()) {
          hasInMemory = true;
        }
      }

      if (!(part instanceof MultiselectPart.Attachments)) {
        if (messageRecord.getBody().length() > 0) {
          hasText = true;
        }
      } else {
        mediaIsSelected = true;
        if (messageRecord.isMediaPending()) {
          hasPendingMedia = true;
        }
      }

      if (messageRecord.isMms() && !((MmsMessageRecord) messageRecord).getSharedContacts().isEmpty()) {
        sharedContact = true;
      }

      if (messageRecord.isViewOnce()) {
        viewOnce = true;
      }

      if (messageRecord.isRemoteDelete()) {
        remoteDelete = true;
      }
    }

    boolean shouldShowForwardAction = !actionMessage   &&
                                      !sharedContact   &&
                                      !viewOnce        &&
                                      !remoteDelete    &&
                                      !hasPendingMedia &&
                                      ((FeatureFlags.forwardMultipleMessages() && selectedParts.size() <= MAX_FORWARDABLE_COUNT) || selectedParts.size() == 1);

    int uniqueRecords = selectedParts.stream()
                                     .map(MultiselectPart::getMessageRecord)
                                     .collect(Collectors.toSet())
                                     .size();

    if (uniqueRecords > 1) {
      builder.shouldShowForwardAction(shouldShowForwardAction)
             .shouldShowReplyAction(false)
             .shouldShowDetailsAction(false)
             .shouldShowSaveAttachmentAction(false)
             .shouldShowResendAction(false);
    } else {
      MessageRecord messageRecord = selectedParts.iterator().next().getMessageRecord();

      builder.shouldShowResendAction(messageRecord.isFailed())
             .shouldShowSaveAttachmentAction(mediaIsSelected                                             &&
                                             !actionMessage                                              &&
                                             !viewOnce                                                   &&
                                             messageRecord.isMms()                                       &&
                                             !hasPendingMedia                                            &&
                                             !messageRecord.isMmsNotification()                          &&
                                             ((MediaMmsMessageRecord)messageRecord).containsMediaSlide() &&
                                             ((MediaMmsMessageRecord)messageRecord).getSlideDeck().getStickerSlide() == null)
             .shouldShowForwardAction(shouldShowForwardAction)
             .shouldShowDetailsAction(!actionMessage)
             .shouldShowReplyAction(canReplyToMessage(conversationRecipient, actionMessage, messageRecord, shouldShowMessageRequest));
    }

    return builder.shouldShowCopyAction(!actionMessage && !remoteDelete && hasText)
                  .shouldShowDeleteAction(!hasInMemory && onlyContainsCompleteMessages(selectedParts))
                  .build();
  }

  private static boolean onlyContainsCompleteMessages(@NonNull Set<MultiselectPart> multiselectParts) {
    return multiselectParts.stream()
                           .map(MultiselectPart::getConversationMessage)
                           .map(ConversationMessage::getMultiselectCollection)
                           .allMatch(collection -> multiselectParts.containsAll(collection.toSet()));
  }

  static boolean canReplyToMessage(@NonNull Recipient conversationRecipient, boolean actionMessage, @NonNull MessageRecord messageRecord, boolean isDisplayingMessageRequest) {
    return !actionMessage                                                              &&
           !messageRecord.isRemoteDelete()                                             &&
           !messageRecord.isPending()                                                  &&
           !messageRecord.isFailed()                                                   &&
           !isDisplayingMessageRequest                                                 &&
           messageRecord.isSecure()                                                    &&
           (!conversationRecipient.isGroup() || conversationRecipient.isActiveGroup()) &&
           !messageRecord.getRecipient().isBlocked();
  }

  static boolean canDeleteMessage(@NonNull MessageRecord messageRecord) {
    return (!isActionMessage(messageRecord) || !messageRecord.isInMemoryMessageRecord());
  }


  static boolean canCopyMessage(@NonNull MessageRecord messageRecord) {
    return (!isActionMessage(messageRecord) && !messageRecord.isRemoteDelete() && messageRecord.getBody().length() > 0);
  }

  static boolean canForwardMessage(@NonNull MessageRecord messageRecord) {
    return (!isActionMessage(messageRecord) && (!messageRecord.isMms() || ((MmsMessageRecord) messageRecord).getSharedContacts().isEmpty()) &&
            !messageRecord.isViewOnce() && !messageRecord.isRemoteDelete() && !messageRecord.isMediaPending());
  }

  static boolean canShowMessageDetails(@NonNull MessageRecord messageRecord) {
    return (!isActionMessage(messageRecord));
  }

  static boolean isActionMessage(@NonNull MessageRecord messageRecord) {
    return messageRecord.isGroupAction() ||
           messageRecord.isCallLog() ||
           messageRecord.isJoined() ||
           messageRecord.isExpirationTimerUpdate() ||
           messageRecord.isEndSession() ||
           messageRecord.isIdentityUpdate() ||
           messageRecord.isIdentityVerified() ||
           messageRecord.isIdentityDefault() ||
           messageRecord.isProfileChange() ||
           messageRecord.isGroupV1MigrationEvent() ||
           messageRecord.isChatSessionRefresh() ||
           messageRecord.isInMemoryMessageRecord();
  }

  private final static class Builder {

    private boolean forward;
    private boolean reply;
    private boolean details;
    private boolean saveAttachment;
    private boolean resend;
    private boolean copy;
    private boolean delete;

    @NonNull Builder shouldShowForwardAction(boolean forward) {
      this.forward = forward;
      return this;
    }

    @NonNull Builder shouldShowReplyAction(boolean reply) {
      this.reply = reply;
      return this;
    }

    @NonNull Builder shouldShowDetailsAction(boolean details) {
      this.details = details;
      return this;
    }

    @NonNull Builder shouldShowSaveAttachmentAction(boolean saveAttachment) {
      this.saveAttachment = saveAttachment;
      return this;
    }

    @NonNull Builder shouldShowResendAction(boolean resend) {
      this.resend = resend;
      return this;
    }

    @NonNull Builder shouldShowCopyAction(boolean copy) {
      this.copy = copy;
      return this;
    }

    @NonNull Builder shouldShowDeleteAction(boolean delete) {
      this.delete = delete;
      return this;
    }

    @NonNull
    MenuState build() {
      return new MenuState(this);
    }
  }
}
