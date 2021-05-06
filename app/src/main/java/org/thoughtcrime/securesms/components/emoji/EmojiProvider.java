package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiDrawInfo;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiParser;
import org.thoughtcrime.securesms.emoji.EmojiBitmapDecoder;
import org.thoughtcrime.securesms.emoji.EmojiSource;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.util.DeviceProperties;

class EmojiProvider {

  private static final    String        TAG      = Log.tag(EmojiProvider.class);
  private static volatile EmojiProvider instance = null;
  private static final    Paint         paint    = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

  private final Context context;

  public static EmojiProvider getInstance(Context context) {
    if (instance == null) {
      synchronized (EmojiProvider.class) {
        if (instance == null) {
          instance = new EmojiProvider(context);
        }
      }
    }
    return instance;
  }

  private EmojiProvider(Context context) {
    this.context = context.getApplicationContext();
  }

  @Nullable EmojiParser.CandidateList getCandidates(@Nullable CharSequence text) {
    if (text == null) return null;
    return new EmojiParser(EmojiSource.getLatest().getEmojiTree()).findCandidates(text);
  }

  @Nullable Spannable emojify(@Nullable CharSequence text, @NonNull TextView tv) {
    return emojify(getCandidates(text), text, tv);
  }

  @Nullable Spannable emojify(@Nullable EmojiParser.CandidateList matches,
                              @Nullable CharSequence text,
                              @NonNull TextView tv)
  {
    if (matches == null || text == null) return null;
    SpannableStringBuilder builder = new SpannableStringBuilder(text);

    for (EmojiParser.Candidate candidate : matches) {
      Drawable drawable = getEmojiDrawable(candidate.getDrawInfo());

      if (drawable != null) {
        builder.setSpan(new EmojiSpan(drawable, tv), candidate.getStartIndex(), candidate.getEndIndex(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }

    return builder;
  }

  @Nullable Drawable getEmojiDrawable(CharSequence emoji) {
    EmojiDrawInfo drawInfo = EmojiSource.getLatest().getEmojiTree().getEmoji(emoji, 0, emoji.length());
    return getEmojiDrawable(drawInfo);
  }

  private @Nullable Drawable getEmojiDrawable(@Nullable EmojiDrawInfo drawInfo) {
    if (drawInfo == null) {
      return null;
    }

    final int           lowMemoryDecodeScale = DeviceProperties.isLowMemoryDevice(context) ? 2 : 1;
    final EmojiSource   source               = EmojiSource.getLatest();
    final EmojiDrawable drawable             = new EmojiDrawable(source, drawInfo, lowMemoryDecodeScale);
    GlideApp.with(context)
            .asBitmap()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .load(drawInfo.getPage().getModel())
            .priority(Priority.HIGH)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .apply(new RequestOptions().set(EmojiBitmapDecoder.OPTION, lowMemoryDecodeScale))
            .addListener(new RequestListener<Bitmap>() {
              @Override
              public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                Log.d(TAG, "Failed to load emoji bitmap resource", e);
                return false;
              }

              @Override
              public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                ThreadUtil.runOnMain(() -> drawable.setBitmap(resource));
                return true;
              }
            })
            .submit();

    return drawable;
  }

  static final class EmojiDrawable extends Drawable {
    private final float intrinsicWidth;
    private final float intrinsicHeight;
    private final Rect  emojiBounds;

    private Bitmap bmp;

    @Override
    public int getIntrinsicWidth() {
      return (int) intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
      return (int) intrinsicHeight;
    }

    EmojiDrawable(@NonNull EmojiSource source, @NonNull EmojiDrawInfo info, int lowMemoryDecodeScale) {
      this.intrinsicWidth  = (source.getMetrics().getRawWidth() * source.getDecodeScale()) / lowMemoryDecodeScale;
      this.intrinsicHeight = (source.getMetrics().getRawHeight() * source.getDecodeScale()) / lowMemoryDecodeScale;

      final int glyphWidth  = (int) (intrinsicWidth);
      final int glyphHeight = (int) (intrinsicHeight);
      final int index       = info.getIndex();
      final int emojiPerRow = source.getMetrics().getPerRow();
      final int xStart      = (index % emojiPerRow) * glyphWidth;
      final int yStart      = (index / emojiPerRow) * glyphHeight;

      this.emojiBounds = new Rect(xStart,
                                  yStart,
                                  xStart + glyphWidth,
                                  yStart + glyphHeight);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
      if (bmp == null) {
        return;
      }

      canvas.drawBitmap(bmp,
                        emojiBounds,
                        getBounds(),
                        paint);
    }

    public void setBitmap(Bitmap bitmap) {
      ThreadUtil.assertMainThread();
      if (bmp == null || !bmp.sameAs(bitmap)) {
        bmp = bitmap;
        invalidateSelf();
      }
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter cf) { }
  }
}
