/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.dependencies

import android.content.Context
import org.signal.core.util.billing.BillingDependencies

/**
 * Dependency object for Google Play Billing.
 */
object GooglePlayBillingDependencies : BillingDependencies {

  private const val BILLING_PRODUCT_ID_NOT_AVAILABLE = -1000

  override val context: Context get() = AppDependencies.application

  override suspend fun getProductId(): String {
    return ""
  }

  override suspend fun getBasePlanId(): String {
    return "monthly"
  }
}
