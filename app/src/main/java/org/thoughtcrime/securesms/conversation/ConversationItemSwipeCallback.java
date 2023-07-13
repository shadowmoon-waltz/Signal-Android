package org.thoughtcrime.securesms.conversation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement;
import org.thoughtcrime.securesms.util.AccessibilityUtil;
import org.thoughtcrime.securesms.util.ServiceUtil;

import java.util.Objects;

public class ConversationItemSwipeCallback extends ItemTouchHelper.SimpleCallback {

  private static float SWIPE_SUCCESS_DX           = ConversationSwipeAnimationHelper.TRIGGER_DX;
  private static long  SWIPE_SUCCESS_VIBE_TIME_MS = 10;

  private boolean swipeBack;
  private boolean shouldTriggerSwipeFeedback;
  private boolean canTriggerSwipe;
  private float   latestDownX;
  private float   latestDownY;
  private float   latestDx;
  
  private final SwipeAvailabilityProvider     swipeAvailabilityProvider;
  private final ConversationItemTouchListener itemTouchListener;
  private final OnSwipeListener               onSwipeListener;

  private final SwipeAvailabilityProvider     swipeAvailabilityProvider2;
  private final OnSwipeListener               onSwipeListener2;

  public ConversationItemSwipeCallback(@NonNull SwipeAvailabilityProvider swipeAvailabilityProvider,
                                       @NonNull OnSwipeListener onSwipeListener,
                                       @Nullable SwipeAvailabilityProvider swipeAvailabilityProvider2,
                                       @Nullable OnSwipeListener onSwipeListener2)
  {
    super(0, ItemTouchHelper.START | ItemTouchHelper.END);
    this.itemTouchListener          = new ConversationItemTouchListener(this::updateLatestDownCoordinate);
    this.swipeAvailabilityProvider  = swipeAvailabilityProvider;
    this.onSwipeListener            = onSwipeListener;
    this.swipeAvailabilityProvider2 = swipeAvailabilityProvider2;
    this.onSwipeListener2           = onSwipeListener2;
    this.shouldTriggerSwipeFeedback = true;
    this.canTriggerSwipe            = true;
  }

  public void attachToRecyclerView(@NonNull RecyclerView recyclerView) {
    recyclerView.addOnItemTouchListener(itemTouchListener);
    new ItemTouchHelper(this).attachToRecyclerView(recyclerView);
  }

  @Override
  public boolean onMove(@NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        @NonNull RecyclerView.ViewHolder target)
  {
    return false;
  }

  @Override
  public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
  }

  @Override
  public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder)
  {
    return (!cannotSwipeViewHolder(viewHolder, -1.0f) ? ItemTouchHelper.START : 0) |
           (!cannotSwipeViewHolder(viewHolder, 1.0f) ? ItemTouchHelper.END : 0);
  }

  @Override
  public int convertToAbsoluteDirection(int flags, int layoutDirection) {
    if (swipeBack) {
      swipeBack = false;
      return 0;
    }
    return super.convertToAbsoluteDirection(flags, layoutDirection);
  }

  @Override
  public void onChildDraw(
          @NonNull Canvas c,
          @NonNull RecyclerView recyclerView,
          @NonNull RecyclerView.ViewHolder viewHolder,
          float dx, float dy, int actionState, boolean isCurrentlyActive)
  {
    final float sign = getSignFromDirection(viewHolder.itemView);
    final float dx2 = sign * dx;

    if (actionState == ItemTouchHelper.ACTION_STATE_IDLE || dx == 0) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), 0, 1, false);
      recyclerView.invalidate();

      if (dx == 0) {
        shouldTriggerSwipeFeedback = true;
        canTriggerSwipe            = true;
      }
    } else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
      final boolean cannotSwipe = cannotSwipeViewHolder(viewHolder, dx2);
      if (cannotSwipe && canTriggerSwipe) return;


      if (!sameSign(dx2, latestDx)) {
        shouldTriggerSwipeFeedback = true;
        canTriggerSwipe            = true;
      }

      final boolean swipeToLeft = dx2 < 0.0f;

      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), Math.abs(dx), sign, swipeToLeft);
      recyclerView.invalidate();
      handleSwipeFeedback(recyclerView.getContext(), requireInteractiveConversationElement(viewHolder), Math.abs(dx), swipeToLeft);
      if (canTriggerSwipe) {
        setTouchListener(recyclerView, viewHolder, Math.abs(dx), swipeToLeft);
      }
    }

    latestDx = dx2;
  }

  private void handleSwipeFeedback(@NonNull Context context, @NonNull InteractiveConversationElement interactiveConversationElement, float dx, boolean swipeToLeft) {
    if (dx > SWIPE_SUCCESS_DX && shouldTriggerSwipeFeedback) {
      vibrate(context);
      ConversationSwipeAnimationHelper.trigger(interactiveConversationElement, swipeToLeft);
      shouldTriggerSwipeFeedback = false;
    }
  }

  private void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, @NonNull MotionEvent motionEvent, boolean swipeToLeft) {
    if (cannotSwipeViewHolder(viewHolder, (!swipeToLeft ? 1.0f : -1.0f))) return;

    InteractiveConversationElement element = requireInteractiveConversationElement(viewHolder);

    if (!swipeToLeft) {
      if (onSwipeListener != null) {
        onSwipeListener.onSwipe(element.getConversationMessage());
      }
    } else {
      if (onSwipeListener2 != null) {
        onSwipeListener2.onSwipe(element.getConversationMessage());
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder,
                                float dx,
                                boolean swipeToLeft)
  {
    recyclerView.setOnTouchListener((v, event) -> {
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          shouldTriggerSwipeFeedback = true;
          break;
        case MotionEvent.ACTION_UP:
          handleTouchActionUp(recyclerView, viewHolder, dx, event, swipeToLeft);
        case MotionEvent.ACTION_CANCEL:
          swipeBack = true;
          shouldTriggerSwipeFeedback = false;
          resetProgressIfAnimationsDisabled(recyclerView, viewHolder);
          break;
      }
      return false;
    });
  }

  private void handleTouchActionUp(@NonNull RecyclerView recyclerView,
                                   @NonNull RecyclerView.ViewHolder viewHolder,
                                   float dx,
                                   @NonNull MotionEvent motionEvent,
                                   boolean swipeToLeft)
  {
    if (dx > SWIPE_SUCCESS_DX) {
      canTriggerSwipe = false;
      onSwiped(viewHolder, motionEvent, swipeToLeft);
      if (shouldTriggerSwipeFeedback) {
        vibrate(viewHolder.itemView.getContext());
      }
      recyclerView.setOnTouchListener(null);
    }
    recyclerView.cancelPendingInputEvents();
  }

  private void resetProgressIfAnimationsDisabled(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
    if (AccessibilityUtil.areAnimationsDisabled(viewHolder.itemView.getContext())) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder),
                                              0f,
                                              getSignFromDirection(viewHolder.itemView),
                                              false);
      recyclerView.invalidate();
      shouldTriggerSwipeFeedback = true;
      canTriggerSwipe = true;
      latestDx = 0.0f;
    }
  }

  private @NonNull InteractiveConversationElement requireInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    return Objects.requireNonNull(getInteractiveConversationElement(viewHolder));
  }

  private @Nullable InteractiveConversationElement getInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (viewHolder instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder;
    } else if (viewHolder.itemView instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder.itemView;
    } else {
      return null;
    }
  }

  private boolean cannotSwipeViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, float direction) {
    InteractiveConversationElement element = getInteractiveConversationElement(viewHolder);
    if (element == null) {
      return true;
    }

    final boolean canSwipeToRight = (direction >= 0.0f && swipeAvailabilityProvider != null && swipeAvailabilityProvider.isSwipeAvailable(element.getConversationMessage()));
    final boolean canSwipeToLeft = (direction <= 0.0f && swipeAvailabilityProvider2 != null && swipeAvailabilityProvider2.isSwipeAvailable(element.getConversationMessage()));

    return (!canSwipeToRight && !canSwipeToLeft) || element.disallowSwipe(latestDownX, latestDownY);
  }

  private void updateLatestDownCoordinate(float x, float y) {
    latestDownX = x;
    latestDownY = y;
  }

  private static float getSignFromDirection(@NonNull View view) {
    return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1f : 1f;
  }

  private static boolean sameSign(float dX, float sign) {
    return dX * sign > 0;
  }

  private static void vibrate(@NonNull Context context) {
    Vibrator vibrator = ServiceUtil.getVibrator(context);
    if (vibrator != null) vibrator.vibrate(SWIPE_SUCCESS_VIBE_TIME_MS);
  }

  public interface SwipeAvailabilityProvider {
    boolean isSwipeAvailable(ConversationMessage conversationMessage);
  }

  public interface OnSwipeListener {
    void onSwipe(ConversationMessage conversationMessage);
  }
}
