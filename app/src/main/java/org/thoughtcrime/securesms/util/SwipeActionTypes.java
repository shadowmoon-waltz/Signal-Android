/**
 * Copyright (C) 2021 shadowmoon_waltz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.util;

// keep in sync with string array pref_swipe_to_left_action_values and pref_swipe_to_right_action_values

public interface SwipeActionTypes {
  public static final String DEFAULT = "default";
  public static final String NONE = "none";
  public static final String REPLY = "reply";
  public static final String DELETE = "delete";
  public static final String DELETE_NO_PROMPT = "delete_no_prompt";
  public static final String COPY_TEXT = "copy_text";
  public static final String COPY_TEXT_POPUP = "copy_text_popup";
  public static final String FORWARD = "forward";
  public static final String MESSAGE_DETAILS = "message_details";
  public static final String SHOW_OPTIONS = "show_options";
  public static final String NOTE_TO_SELF = "note_to_self";
  public static final String MULTI_SELECT = "multi_select";

  public static final String DEFAULT_FOR_RIGHT = REPLY;
  public static final String DEFAULT_FOR_LEFT = NONE;

  public static final int DEFAULT_DRAWABLE_FOR_RIGHT = org.thoughtcrime.securesms.R.drawable.symbol_reply_24;
  public static final int DEFAULT_DRAWABLE_FOR_LEFT = org.thoughtcrime.securesms.R.drawable.symbol_info_24;
}
