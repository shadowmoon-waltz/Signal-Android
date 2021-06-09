# SignalSW

This is a fork of Signal, mostly small changes for my personal use.

License is unchanged (GPLv3 license). Changes from the non-fork in this repository are not made under their CLA (as I have not submitted pull requests), and you may submit a pull request to this fork without signing a CLA.

Differences from stock Signal:
* Package name is different, so the fork can be installed on the same device as stock Signal. Note that a phone number can be registered to only one Signal (non-fork or fork) instance at a time (the older one will be deregistered, and if a backup is not restored during install time on the new instance, the identity key will change, chat history will be lost, etc). The fork should be compatiable with stock Signal backups and vice versa (but not really tested).
* Build signing code is slightly tweaked to my preferred style (note that the build target I use is assembleSwProdRelease).
* As I may not provide support, the sending debug log feature is disabled.
* Update check is not currently set up for the fork and is disabled.
* Option to hide insights option when sms enabled (just tells you how much of your messages sent were encrypted).
* Option to show read reaction timestamp.
* Option to view/set identity keys (very specific use cases, may break Signal installation, please read security warnings when clicking the button in the view/set screen that populates your public and private identity keys).
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
    * show options (show long press menu)
    * note to self (shortcut for forward to self, only works if you have an existing note to self conversation)
    * select multiple (select item and enter multi select mode)
* Option to enable a range to be selected when selecting multiple conversation items, by long pressing an item after selecting an item, which will select all items in that range (including the selected and long pressed items)
* Option to enable select multiple mode when long pressing any type of conversation item (most useful when setting swipe to right conversation item action to show options)
The original README follows, and may not reflect changes in the fork.

## Original README (Signal Android)

Signal is a messaging app for simple private communication with friends.

Signal uses your phone's data connection (WiFi/3G/4G) to communicate securely, optionally supports plain SMS/MMS to function as a unified messenger, and can also encrypt the stored messages on your phone.

Currently available on the Play store.

<a href='https://play.google.com/store/apps/details?id=org.thoughtcrime.securesms&pcampaignid=MKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png' height='80px'/></a>

## Contributing Bug reports
We use GitHub for bug tracking. Please search the existing issues for your bug and create a new one if the issue is not yet tracked!

https://github.com/signalapp/Signal-Android/issues

## Joining the Beta
Want to live life on the bleeding edge and help out with testing?

You can subscribe to Signal Android Beta releases here:
https://play.google.com/apps/testing/org.thoughtcrime.securesms
 
If you're interested in a life of peace and tranquility, stick with the standard releases.

## Contributing Translations
Interested in helping to translate Signal? Contribute here:

https://www.transifex.com/projects/p/signal-android/

## Contributing Code

If you're new to the Signal codebase, we recommend going through our issues and picking out a simple bug to fix (check the "easy" label in our issues) in order to get yourself familiar. Also please have a look at the [CONTRIBUTING.md](https://github.com/signalapp/Signal-Android/blob/master/CONTRIBUTING.md), that might answer some of your questions.

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

Copyright 2013-2020 Signal

Licensed under the GPLv3: http://www.gnu.org/licenses/gpl-3.0.html

Google Play and the Google Play logo are trademarks of Google Inc.
