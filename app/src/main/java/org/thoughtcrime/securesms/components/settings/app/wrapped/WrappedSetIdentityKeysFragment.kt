package org.thoughtcrime.securesms.components.settings.app.wrapped

import androidx.fragment.app.Fragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.preferences.SetIdentityKeysFragment

class WrappedSetIdentityKeysFragment : SettingsWrapperFragment() {
  override fun getFragment(): Fragment {
    toolbar.setTitle(R.string.ForkSettingsFragment__view_set_identity_keys)
    return SetIdentityKeysFragment()
  }
}
