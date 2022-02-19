package org.thoughtcrime.securesms.sharing;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.stickers.StickerLocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ShareIntents {

  private static final String EXTRA_MEDIA      = "extra_media";
  private static final String EXTRA_BORDERLESS = "extra_borderless";
  private static final String EXTRA_STICKER    = "extra_sticker";
  public  static final String EXTRA_VIDEO_GIF  = "extra_video_gif";

  private ShareIntents() {
  }

  public static final class Args {

    private final CharSequence     extraText;
    private final ArrayList<Media> extraMedia;
    private final StickerLocator   extraSticker;
    private final boolean          isBorderless;
    private final boolean          isVideoGif;

    public static Args from(@NonNull Intent intent) {
      return new Args(intent.getStringExtra(Intent.EXTRA_TEXT),
                      intent.getParcelableArrayListExtra(EXTRA_MEDIA),
                      intent.getParcelableExtra(EXTRA_STICKER),
                      intent.getBooleanExtra(EXTRA_BORDERLESS, false),
                      intent.getBooleanExtra(EXTRA_VIDEO_GIF, false));
    }

    private Args(@Nullable CharSequence extraText,
                 @Nullable ArrayList<Media> extraMedia,
                 @Nullable StickerLocator extraSticker,
                 boolean isBorderless,
                 boolean isVideoGif)
    {
      this.extraText    = extraText;
      this.extraMedia   = extraMedia;
      this.extraSticker = extraSticker;
      this.isBorderless = isBorderless;
      this.isVideoGif   = isVideoGif;
    }

    public @Nullable ArrayList<Media> getExtraMedia() {
      return extraMedia;
    }

    public @Nullable CharSequence getExtraText() {
      return extraText;
    }

    public @Nullable StickerLocator getExtraSticker() {
      return extraSticker;
    }

    public boolean isBorderless() {
      return isBorderless;
    }

    public boolean isVideoGif() {
      return isVideoGif;
    }

    public boolean isEmpty() {
      return extraSticker == null                         &&
             (extraMedia == null || extraMedia.isEmpty()) &&
             TextUtils.isEmpty(extraText);
    }
  }

  public static final class Builder {

    private final Context context;

    private String      extraText;
    private List<Media> extraMedia;
    private Slide       slide;
    private boolean     noteToSelf;

    public Builder(@NonNull Context context) {
      this.context = context;
    }

    public @NonNull Builder setText(@NonNull CharSequence extraText) {
      this.extraText = extraText.toString();
      return this;
    }

    public @NonNull Builder setMedia(@NonNull Collection<Media> extraMedia) {
      this.extraMedia = new ArrayList<>(extraMedia);
      return this;
    }

    public @NonNull Builder setSlide(@NonNull Slide slide) {
      this.slide = slide;
      return this;
    }

    public @NonNull Builder setNoteToSelf(boolean noteToSelf) {
      this.noteToSelf = noteToSelf;
      return this;
    }

    public @NonNull Intent build() {
      if (slide != null && extraMedia != null) {
        throw new IllegalStateException("Cannot create intent with both Slide and [Media]");
      }

      Intent intent = new Intent(context, ShareActivity.class);

      intent.putExtra(Intent.EXTRA_TEXT, extraText);

      if (extraMedia != null) {
        intent.putParcelableArrayListExtra(EXTRA_MEDIA, new ArrayList<>(extraMedia));
      } else if (slide != null) {
        intent.putExtra(Intent.EXTRA_STREAM, slide.getUri());
        intent.putExtra(EXTRA_BORDERLESS, slide.isBorderless());

        if (slide.hasSticker()) {
          intent.putExtra(EXTRA_STICKER, slide.asAttachment().getSticker());
          intent.setType(slide.asAttachment().getContentType());
        } else {
          intent.setType(slide.getContentType());
        }
      }

      if (noteToSelf) {
        final Recipient recipient = Recipient.self();
        intent.putExtra(ShareActivity.EXTRA_RECIPIENT_ID, Long.toString(recipient.getId().toLong()));
        intent.putExtra(ShareActivity.EXTRA_THREAD_ID, SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId()));
        intent.putExtra(ShareActivity.EXTRA_DISTRIBUTION_TYPE, 0);
      }

      return intent;
    }
  }
}
