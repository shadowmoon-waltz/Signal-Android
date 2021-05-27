package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.List;

public class ConversationTypingView extends LinearLayout {

  private AvatarImageView     avatar;
  private View                bubble;
  private TypingIndicatorView indicator;

  public ConversationTypingView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    avatar    = findViewById(R.id.typing_avatar);
    bubble    = findViewById(R.id.typing_bubble);
    indicator = findViewById(R.id.typing_indicator);
  }

  public void setTypists(@NonNull GlideRequests glideRequests, @NonNull List<Recipient> typists, boolean isGroupThread, boolean hasWallpaper) {
    if (typists.isEmpty()) {
      indicator.stopAnimation();
      return;
    }

    Recipient typist = typists.get(0);

    if (isGroupThread) {
      avatar.setAvatar(glideRequests, typist, true);
      avatar.setVisibility(VISIBLE);
    } else {
      avatar.setVisibility(GONE);
    }

    if (hasWallpaper) {
      bubble.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.conversation_item_wallpaper_bubble_color));
    } else {
      bubble.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.signal_background_secondary));
    }

    indicator.startAnimation();
  }
}
