package org.signal.billing

import android.content.Context
import org.signal.core.util.billing.BillingApi

/**
 * SW builds do not support google play billing.
 */
object BillingFactory {
  @JvmStatic
  fun create(context: Context, isBackupsAvailable: Boolean): BillingApi {
    return BillingApi.Empty
  }
}
