package org.thoughtcrime.securesms.components.settings.app.fork

import androidx.lifecycle.ViewModelProviders
import androidx.navigation.Navigation
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsAdapter
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.util.TextSecurePreferences

class ForkSettingsFragment : DSLSettingsFragment(R.string.preferences__fork_specific) {

  private lateinit var viewModel: ForkSettingsViewModel

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    viewModel = ViewModelProviders.of(this)[ForkSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) { state ->
      adapter.submitList(getConfiguration(state).toMappingModelList())
    }
  }

  private fun getConfiguration(state: ForkSettingsState): DSLConfiguration {
    return configure {
      switchPref(
        title = DSLSettingsText.from(R.string.ForkSettingsFragment__hide_insights),
        summary = DSLSettingsText.from(R.string.ForkSettingsFragment__hide_insights_summary),
        isChecked = state.hideInsights,
        onClick = {
          viewModel.setHideInsights(!state.hideInsights)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.ForkSettingsFragment__show_reaction_timestamps),
        isChecked = state.showReactionTimestamps,
        onClick = {
          viewModel.setShowReactionTimestamps(!state.showReactionTimestamps)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.ForkSettingsFragment__force_websocket_mode),
        summary = DSLSettingsText.from(R.string.ForkSettingsFragment__force_websocket_mode_summary),
        isChecked = state.forceWebsocketMode,
        onClick = {
          viewModel.setForceWebsocketMode(!state.forceWebsocketMode)
        }
      )

      clickPref(
        title = DSLSettingsText.from(R.string.ForkSettingsFragment__view_set_identity_keys),
        onClick = {
          Navigation.findNavController(requireView()).navigate(R.id.action_forkSettingsFragment_to_setIdentityKeysFragment)
        }
      )
    }
  }
}
