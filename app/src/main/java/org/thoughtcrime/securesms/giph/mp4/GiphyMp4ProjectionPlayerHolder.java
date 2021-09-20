package org.thoughtcrime.securesms.giph.mp4;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.Projection;

import java.util.ArrayList;
import java.util.List;

/**
 * Object which holds on to an injected video player.
 */
public final class GiphyMp4ProjectionPlayerHolder implements Player.Listener {
  private final FrameLayout         container;
  private final GiphyMp4VideoPlayer player;

  private Runnable                       onPlaybackReady;
  private MediaItem                      mediaItem;
  private GiphyMp4PlaybackPolicyEnforcer policyEnforcer;

  private GiphyMp4ProjectionPlayerHolder(@NonNull FrameLayout container, @NonNull GiphyMp4VideoPlayer player) {
    this.container = container;
    this.player    = player;
  }

  @NonNull FrameLayout getContainer() {
    return container;
  }

  public void playContent(@NonNull MediaItem mediaItem, @Nullable GiphyMp4PlaybackPolicyEnforcer policyEnforcer) {
    this.mediaItem      = mediaItem;
    this.policyEnforcer = policyEnforcer;

    player.setVideoItem(mediaItem);
    player.play();
  }

  public void clearMedia() {
    this.mediaItem      = null;
    this.policyEnforcer = null;
    player.stop();
  }

  public @Nullable MediaItem getMediaItem() {
    return mediaItem;
  }

  public void setOnPlaybackReady(@Nullable Runnable onPlaybackReady) {
    this.onPlaybackReady = onPlaybackReady;
    if (onPlaybackReady != null && player.getPlaybackState() == Player.STATE_READY) {
      onPlaybackReady.run();
    }
  }

  public void hide() {
    container.setVisibility(View.GONE);
  }

  public void show() {
    container.setVisibility(View.VISIBLE);
  }

  @Override
  public void onPlaybackStateChanged(int playbackState) {
    if (playbackState == Player.STATE_READY) {
      if (onPlaybackReady != null) {
        if (policyEnforcer != null) {
          policyEnforcer.setMediaDuration(player.getDuration());
        }
        onPlaybackReady.run();
      }
    }
  }

  @Override
  public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                      @NonNull Player.PositionInfo newPosition,
                                      int reason)
  {
    if (policyEnforcer != null && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
      if (policyEnforcer.endPlayback()) {
        player.stop();
      }
    }
  }

  public static @NonNull List<GiphyMp4ProjectionPlayerHolder> injectVideoViews(@NonNull Context context,
                                                                               @NonNull Lifecycle lifecycle,
                                                                               @NonNull ViewGroup viewGroup,
                                                                               int nPlayers)
  {
    List<GiphyMp4ProjectionPlayerHolder> holders        = new ArrayList<>(nPlayers);
    GiphyMp4ExoPlayerProvider            playerProvider = new GiphyMp4ExoPlayerProvider(context);

    for (int i = 0; i < nPlayers; i++) {
      FrameLayout container = (FrameLayout) LayoutInflater.from(context)
                                                          .inflate(R.layout.giphy_mp4_player, viewGroup, false);
      GiphyMp4VideoPlayer            player    = container.findViewById(R.id.video_player);
      ExoPlayer                      exoPlayer = playerProvider.create();
      GiphyMp4ProjectionPlayerHolder holder    = new GiphyMp4ProjectionPlayerHolder(container, player);

      lifecycle.addObserver(player);
      player.setExoPlayer(exoPlayer);
      player.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
      exoPlayer.addListener(holder);

      holders.add(holder);
      viewGroup.addView(container);
    }

    return holders;
  }

  public void setCorners(@Nullable Projection.Corners corners) {
    player.setCorners(corners);
  }
}
