package org.thoughtcrime.securesms.subscription

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.signal.core.util.money.FiatMoney
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.badges.BadgeImageView
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.payments.FiatMoneyUtil
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.MappingAdapter
import org.thoughtcrime.securesms.util.MappingViewHolder
import org.thoughtcrime.securesms.util.visible
import java.util.Locale

/**
 * Represents a Subscription that a user can start.
 */
data class Subscription(
  val id: String,
  val title: String,
  val badge: Badge,
  val price: FiatMoney,
  val level: Int,
) {

  companion object {
    fun register(adapter: MappingAdapter) {
      adapter.registerFactory(Model::class.java, MappingAdapter.LayoutFactory({ ViewHolder(it) }, R.layout.subscription_preference))
    }
  }

  class Model(
    val subscription: Subscription,
    val isSelected: Boolean,
    val isActive: Boolean,
    val willRenew: Boolean,
    override val isEnabled: Boolean,
    val onClick: () -> Unit,
    val renewalTimestamp: Long
  ) : PreferenceModel<Model>(isEnabled = isEnabled) {

    override fun areItemsTheSame(newItem: Model): Boolean {
      return subscription.id == newItem.subscription.id
    }

    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) &&
        newItem.subscription == subscription &&
        newItem.isSelected == isSelected &&
        newItem.isActive == isActive &&
        newItem.renewalTimestamp == renewalTimestamp &&
        newItem.willRenew == willRenew
    }

    override fun getChangePayload(newItem: Model): Any? {
      return if (newItem.subscription.badge == subscription.badge) {
        Unit
      } else {
        null
      }
    }
  }

  class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {

    private val badge: BadgeImageView = itemView.findViewById(R.id.badge)
    private val title: TextView = itemView.findViewById(R.id.title)
    private val tagline: TextView = itemView.findViewById(R.id.tagline)
    private val price: TextView = itemView.findViewById(R.id.price)
    private val check: ImageView = itemView.findViewById(R.id.check)

    override fun bind(model: Model) {
      itemView.isEnabled = model.isEnabled
      itemView.setOnClickListener { model.onClick() }
      itemView.isSelected = model.isSelected

      if (payload.isEmpty()) {
        badge.setBadge(model.subscription.badge)
      }

      title.text = model.subscription.title
      tagline.text = context.getString(R.string.Subscription__earn_a_s_badge, model.subscription.badge.name)

      val formattedPrice = FiatMoneyUtil.format(
        context.resources,
        model.subscription.price,
        FiatMoneyUtil.formatOptions()
      )

      if (model.isActive && model.willRenew) {
        price.text = context.getString(
          R.string.Subscription__s_per_month_dot_renews_s,
          formattedPrice,
          DateUtils.formatDateWithYear(Locale.getDefault(), model.renewalTimestamp)
        )
      } else if (model.isActive) {
        price.text = context.getString(
          R.string.Subscription__s_per_month_dot_expires_s,
          formattedPrice,
          DateUtils.formatDateWithYear(Locale.getDefault(), model.renewalTimestamp)
        )
      } else {
        price.text = context.getString(
          R.string.Subscription__s_per_month,
          formattedPrice
        )
      }

      check.visible = model.isActive
    }
  }
}
