/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc

import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.widget.PopupWindowCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.WebRtcCallActivity
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies

/**
 * A popup window for calls that holds extra actions, such as reactions, raise hand, and screen sharing.
 *
 */
class CallOverflowPopupWindow(private val activity: WebRtcCallActivity, parentViewGroup: ViewGroup) : PopupWindow(
  LayoutInflater.from(activity).inflate(R.layout.call_overflow_holder, parentViewGroup, false),
  activity.resources.getDimension(R.dimen.reaction_scrubber_width).toInt(),
  ViewGroup.LayoutParams.WRAP_CONTENT
) {

  init {
    (contentView as CallReactionScrubber).initialize(activity.supportFragmentManager, activity) {
      ApplicationDependencies.getSignalCallManager().react(it)
      dismiss()
    }
  }

  fun show(anchor: View) {
    isFocusable = true

    val resources = activity.resources

    val margin = resources.getDimension(R.dimen.calling_reaction_scrubber_margin).toInt()

    val windowRect = Rect()
    contentView.getWindowVisibleDisplayFrame(windowRect)
    val windowWidth = windowRect.width()
    val popupWidth = resources.getDimension(R.dimen.reaction_scrubber_width).toInt()

    val popupHeight = resources.getDimension(R.dimen.calling_reaction_emoji_height).toInt()

    val xOffset = windowWidth - popupWidth - margin
    val yOffset = -popupHeight - margin

    PopupWindowCompat.showAsDropDown(this, anchor, xOffset, yOffset, Gravity.NO_GRAVITY)
  }
}
