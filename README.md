# SignalSW

This is a fork of Signal, mostly small changes for my personal use.

License is unchanged (GPLv3 license). You may submit pull requests to this fork and they can be merged without signing the upstream Contributor License Agreement (CLA).

Main gradle build command: assembleSwProdRelease

## Differences from stock Signal

<details open="">
<summary>March 2022</summary>

* Further improvements to security of view/set identity keys screen (requires authentication if device has one available)
* Long-press Signal icon in conversation bubble to hide media/attachment keyboard. Works around possible bug where it may be otherwise difficult to close the media/attachment keyboard in conversation bubbles
* Long-press recipient label that appears above media while adding/editing media (to send in a conversation) to close media selection. Works around possible bug where it is otherwise difficult to close/go back from media selection while in conversation bubbles
</details>

<details open="">
<summary>February 2022</summary>

* Added support for building with GitHub Actions. See below the changelog for more information
* Added option to set backup interval to any number of days (upstream always uses 1). You can also set it to a really big number if you want to only create backups using the option in Chat Backups setting manually
* Upstream has fixed (in a different way) ~~Forwarding video GIFs from the media preview screen now retains video GIF status~~
</details>

<details>
<summary>January 2022</summary>

* Option to show a prompt when sending videos to send like gifs. Videos will autoplay, not have seek controls, will loop, sometimes appear larger than if sent as video, and may not have audio.
* Add sort media preview by content type (and then largest or newest) option
</details>

<details>
<summary>October 2021</summary>

* Option to not prompt when deleting messages using the delete button action bar; they will only be automatically deleted for you, not everyone.
</details>

<details>
<summary>June 2021</summary>

* Several new swipe to right options (see May 2021) swipe to right list of options
* Option to enable select multiple mode when long pressing any type of conversation item (most useful when setting swipe to right conversation item action to show options)
* Due to using a custom build target and not realizing a specific setting needed to add in that build target, the usual gradle build target did not perform dependency verification prior to commit 850f9bfe on 2021-06-09 (part of release 5.14.2), when it was fixed
* Option to also show profile names under contact names in detailed recipient views and group member lists (when a member does not have about set), for recipients with contact entries (by default, contact names replace profile names in most views)
* Option to enable manage group view tweaks, which include hiding text with prompt to add group description or number of members and moving member list almost to top of view (but leaving the add members button where it usually is)
* Added in support for swipe to left conversation item action. Option to customize swipe to right conversation item action has the same options as the swipe to right conversation item action
* Upstream has added feature independently (with better UI) ~~Option to type reaction emoji, by long pressing the custom emoji bottom sheet settings button to use the keyboard to enter reaction emoji. Useful if you want to search for emojis and your keyboard supports it~~
</details>


<details>
<summary>May 2021</summary>

* Can start or join group calls with just microphone permission (non-fork requires video permission)
* Option to long-press a custom emoji previously used as a reaction in the long-press your selected reaction popup to change it to another custom emoji (so you don't have to press it once to deselect it and then select a different one; note that you still have to let go of the custom emoji after long pressing to show the custom emoji selector)
* Option to open popup with editable text when selecting copying text for one or more messages in a conversation, which allows for easy modification and/or copying of part or all of the string
* Option to add menu option to conversation view to delete the current conversation (still prompted with a confirmation popup)
* Option to customize swipe to right conversation item action. Options are
    * reply (non-fork version uses this)
    * do nothing (disable swipe to right)
    * delete message (with usual prompt or for me without prompt)
    * copy text (normal method or with popup)
    * show message details
    * show options (show long press menu) (June 2021)
    * note to self (shortcut for forward to self, only works if you have an existing note to self conversation) (June 2021)
    * select multiple (select item and enter multi select mode) (June 2021)
* Option to enable a range to be selected when selecting multiple conversation items, by long pressing an item after selecting an item, which will select all items in that range (including the selected and long pressed items)
</details>

<details>
<summary>April 2021</summary>

* Package name is different, so the fork can be installed on the same device as stock Signal. Note that a phone number can be registered to only one Signal (non-fork or fork) instance at a time (the older one will be deregistered, and if a backup is not restored during install time on the new instance, the identity key will change, chat history will be lost, etc). The fork should be compatiable with stock Signal backups and vice versa (but not really tested).
* Build signing code is slightly tweaked to my preferred style (note that the build target I use is assembleSwProdRelease).
* As I may not provide support, the sending debug log feature is disabled.
* Update check is not currently set up for the fork and is disabled.
* Option to hide insights option when sms is enabled (just tells you how much of your messages sent were encrypted).
* Option to show read reaction timestamp.
* Option to view/set identity keys (very specific use cases, may break Signal installation, please read security warnings when clicking the button in the view/set screen that populates your public and private identity keys). (hides itself in recent apps screen from January 2022)
</details>

---

<details>
<summary>Instructions for building with GitHub Actions</summary>

We make the upstream Android CI and Reproducible Build workflows on demand only, and add a debug build (and create artifact with universal apk) and a release build (and create release with split and universal apks) workflows. These also sign your apks (use if you're comfortable with the build server signing them). To use these workflows yourself, start by forking this repository. Follow the
[Android developer instructions to generate a private key](https://developer.android.com/studio/build/building-cmdline#sign_cmdline) and then convert it to a base64 string with `openssl base64 < keystore.jks | tr -d '\n' | tee keystore.txt` in a Linux-like terminal and then put it in a GitHub Actions secret called "KEYSTORE_BASE64". Put the keystore password in a secret called "KEYSTORE_PASSWORD", the keystore key alias in "KEYSTORE_ALIAS", and
the keystore key alias password in "KEYSTORE_ALIAS_PASSWORD" ([more info on Github Action secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)).
</details>

---

The Original README follows. It may not reflect changes in the fork.

<details>
<summary>Original README</summary>

Signal is a messaging app for simple private communication with friends.

Signal uses your phone's data connection (WiFi/3G/4G) to communicate securely, optionally supports plain SMS/MMS to function as a unified messenger, and can also encrypt the stored messages on your phone.

Currently available on the Play store and [signal.org](https://signal.org/android/apk/).

<a href='https://play.google.com/store/apps/details?id=org.thoughtcrime.securesms&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height='80px'/></a>

## Contributing Bug reports
We use GitHub for bug tracking. Please search the existing issues for your bug and create a new one if the issue is not yet tracked!

https://github.com/signalapp/Signal-Android/issues

## Joining the Beta
Want to live life on the bleeding edge and help out with testing?

You can subscribe to Signal Android Beta releases here:
https://play.google.com/apps/testing/org.thoughtcrime.securesms
 
If you're interested in a life of peace and tranquility, stick with the standard releases.

## Contributing Code

If you're new to the Signal codebase, we recommend going through our issues and picking out a simple bug to fix (check the "easy" label in our issues) in order to get yourself familiar. Also please have a look at the [CONTRIBUTING.md](https://github.com/signalapp/Signal-Android/blob/main/CONTRIBUTING.md), that might answer some of your questions.

For larger changes and feature ideas, we ask that you propose it on the [unofficial Community Forum](https://community.signalusers.org) for a high-level discussion with the wider community before implementation.

## Contributing Ideas
Have something you want to say about Open Whisper Systems projects or want to be part of the conversation? Get involved in the [community forum](https://community.signalusers.org).

Help
====
## Support
For troubleshooting and questions, please visit our support center!

https://support.signal.org/

## Documentation
Looking for documentation? Check out the wiki!

https://github.com/signalapp/Signal-Android/wiki

# Legal things
## Cryptography Notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See <http://www.wassenaar.org/> for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which includes information security software using or performing cryptographic functions with asymmetric algorithms.
The form and manner of this distribution makes it eligible for export under the License Exception ENC Technology Software Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section 740.13) for both object code and source code.

## License

Copyright 2013-2022 Signal

Licensed under the GPLv3: http://www.gnu.org/licenses/gpl-3.0.html

Google Play and the Google Play logo are trademarks of Google LLC.
</details>
