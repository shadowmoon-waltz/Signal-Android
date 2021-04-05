package org.thoughtcrime.securesms.reactions;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.util.AvatarUtil;
import org.thoughtcrime.securesms.util.DateUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ReactionRecipientsAdapter extends RecyclerView.Adapter<ReactionRecipientsAdapter.ViewHolder> {

  private Locale locale = null;

  private List<ReactionDetails> data = Collections.emptyList();

  public ReactionRecipientsAdapter(Locale locale) {
    this.locale = locale;
  }

  public void updateData(List<ReactionDetails> newData) {
    data = newData;
    notifyDataSetChanged();
  }

  @Override
  public @NonNull ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return new ViewHolder(LayoutInflater.from(parent.getContext())
                                        .inflate(R.layout.reactions_bottom_sheet_dialog_fragment_recipient_item,
                                                 parent,
                                                 false));
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    holder.bind(data.get(position), locale);
  }

  @Override
  public int getItemCount() {
    return data.size();
  }

  static final class ViewHolder extends RecyclerView.ViewHolder {

    private final AvatarImageView avatar;
    private final TextView        recipient;
    private final TextView        time;
    private final TextView        emoji;

    public ViewHolder(@NonNull View itemView) {
      super(itemView);

      avatar    = itemView.findViewById(R.id.reactions_bottom_view_recipient_avatar);
      recipient = itemView.findViewById(R.id.reactions_bottom_view_recipient_name);
      time      = itemView.findViewById(R.id.reactions_bottom_view_recipient_time);
      emoji     = itemView.findViewById(R.id.reactions_bottom_view_recipient_emoji);
    }

    void bind(@NonNull ReactionDetails reaction, Locale locale) {
      this.emoji.setText(reaction.getDisplayEmoji());
      if (locale != null) {
        this.time.setText(DateUtils.getExtendedRelativeTimeSpanString(this.time.getContext(), locale, reaction.getTimestamp()));
      } else {
        this.time.setVisibility(View.GONE);
      }

      if (reaction.getSender().isSelf()) {
        this.recipient.setText(R.string.ReactionsRecipientAdapter_you);
        this.avatar.setAvatar(GlideApp.with(avatar), null, false);
        AvatarUtil.loadIconIntoImageView(reaction.getSender(), avatar);
      } else {
        this.recipient.setText(reaction.getSender().getDisplayName(itemView.getContext()));
        this.avatar.setAvatar(GlideApp.with(avatar), reaction.getSender(), false);
      }
    }
  }

}
