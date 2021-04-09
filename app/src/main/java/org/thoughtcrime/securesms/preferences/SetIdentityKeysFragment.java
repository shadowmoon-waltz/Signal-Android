package org.thoughtcrime.securesms.preferences;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.dd.CircularProgressButton;

import org.thoughtcrime.securesms.R;

import org.thoughtcrime.securesms.util.Base64;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;

import android.widget.Button;

public class SetIdentityKeysFragment extends Fragment {

  private EditText               publicKeyText;
  private EditText               privateKeyText;
  private CircularProgressButton applyButton;
  private Button                 populateButton;

  public static SetIdentityKeysFragment newInstance() {
    return new SetIdentityKeysFragment();
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.set_identity_keys_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    this.publicKeyText  = view.findViewById(R.id.set_identity_keys_public_key);
    this.privateKeyText = view.findViewById(R.id.set_identity_keys_private_key);
    this.applyButton    = view.findViewById(R.id.set_identity_keys_apply);
    this.populateButton = view.findViewById(R.id.set_identity_keys_populate);

    applyButton.setOnClickListener(v -> onApplyClicked());

    populateButton.setOnClickListener(v -> onPopulateClicked());

    requireActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  @Override
  public void onResume() {
    super.onResume();
    ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle("View/Set Identity Keys");
  }

  private void onApplyClicked() {
    if (publicKeyText.getText().length() == 44 && privateKeyText.getText().length() == 44) {
      try {
        final byte[] publicKey = Base64.decode(publicKeyText.getText().toString());
        final byte[] privateKey = Base64.decode(privateKeyText.getText().toString());
        IdentityKeyUtil.setIdentityKeys(requireContext(), publicKey, privateKey);
        new AlertDialog.Builder(requireContext())
                       .setMessage("Identity keys set successfully.")
                       .setPositiveButton(android.R.string.ok, (d, i) -> {
                         d.dismiss();
                       })
                       .show();
        return;
      } catch (Exception e) {

      }
    }

    new AlertDialog.Builder(requireContext())
             .setMessage("Failed to set identity keys. Verify you entered the keys correctly (base 64, 44 characters).")
             .setPositiveButton(android.R.string.ok, (d, i) -> {
               d.dismiss();
             })
             .show();
  }

  private void onPopulateClicked() {
    new AlertDialog.Builder(requireContext())
             .setTitle("WARNING!")
             .setMessage("This will show public and private identity keys. Anyone that has them can potentially impersonate you on Signal. " +
                         "Only view somewhere relatively private, and be careful about it showing up in screenshots, recent apps, the clipboard, " +
                         "and wherever else you choose to store it. Setting it to incorrect values may cause app issues.")
             .setPositiveButton("Proceed", (d, i) -> {
               try {
                 IdentityKeyPair ikp = IdentityKeyUtil.getIdentityKeyPair(requireContext());
                 this.publicKeyText.setText(Base64.encodeBytes(ikp.getPublicKey().serialize()));
                 this.privateKeyText.setText(Base64.encodeBytes(ikp.getPrivateKey().serialize()));
               } catch (Exception e) {

               }
               d.dismiss();
             })
             .setNegativeButton("Cancel", (d, i) -> {
               d.dismiss();
             })
             .show();
  }
}
