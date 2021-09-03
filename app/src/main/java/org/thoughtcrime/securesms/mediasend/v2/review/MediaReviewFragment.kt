package org.thoughtcrime.securesms.mediasend.v2.review

import android.animation.Animator
import android.animation.AnimatorSet
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.keyboard.findListener
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.HudCommand
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionNavigator
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionNavigator.Companion.requestPermissionsForGallery
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionState
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionViewModel
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.MappingAdapter
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Allows the user to view and edit selected media.
 */
class MediaReviewFragment : Fragment(R.layout.v2_media_review_fragment) {

  private val sharedViewModel: MediaSelectionViewModel by viewModels(
    ownerProducer = { requireActivity() }
  )

  private lateinit var callback: Callback

  private lateinit var drawToolButton: View
  private lateinit var cropAndRotateButton: View
  private lateinit var qualityButton: ImageView
  private lateinit var saveButton: View
  private lateinit var sendButton: View
  private lateinit var addMediaButton: View
  private lateinit var viewOnceButton: ViewSwitcher
  private lateinit var viewOnceMessage: TextView
  private lateinit var addMessageButton: TextView
  private lateinit var addMessageEntry: TextView
  private lateinit var recipientDisplay: TextView
  private lateinit var pager: ViewPager2
  private lateinit var controls: ConstraintLayout
  private lateinit var selectionRecycler: RecyclerView
  private lateinit var controlsShade: View

  private val navigator = MediaSelectionNavigator(
    toGallery = R.id.action_mediaReviewFragment_to_mediaGalleryFragment,
  )

  private var animatorSet: AnimatorSet? = null
  private var disposables: CompositeDisposable? = null

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    postponeEnterTransition()

    callback = requireNotNull(findListener())

    drawToolButton = view.findViewById(R.id.draw_tool)
    cropAndRotateButton = view.findViewById(R.id.crop_and_rotate_tool)
    qualityButton = view.findViewById(R.id.quality_selector)
    saveButton = view.findViewById(R.id.save_to_media)
    sendButton = view.findViewById(R.id.send)
    addMediaButton = view.findViewById(R.id.add_media)
    viewOnceButton = view.findViewById(R.id.view_once_toggle)
    addMessageButton = view.findViewById(R.id.add_a_message)
    addMessageEntry = view.findViewById(R.id.add_a_message_entry)
    recipientDisplay = view.findViewById(R.id.recipient)
    pager = view.findViewById(R.id.media_pager)
    controls = view.findViewById(R.id.controls)
    selectionRecycler = view.findViewById(R.id.selection_recycler)
    controlsShade = view.findViewById(R.id.controls_shade)
    viewOnceMessage = view.findViewById(R.id.view_once_message)

    val pagerAdapter = MediaReviewFragmentPagerAdapter(this)

    disposables = CompositeDisposable()
    disposables?.add(
      sharedViewModel.hudCommands.subscribe {
        when (it) {
          HudCommand.ResumeEntryTransition -> startPostponedEnterTransition()
          else -> Unit
        }
      }
    )

    pager.adapter = pagerAdapter

    drawToolButton.setOnClickListener {
      sharedViewModel.sendCommand(HudCommand.StartDraw)
    }

    cropAndRotateButton.setOnClickListener {
      sharedViewModel.sendCommand(HudCommand.StartCropAndRotate)
    }

    qualityButton.setOnClickListener {
      QualitySelectorBottomSheetDialog.show(parentFragmentManager)
    }

    saveButton.setOnClickListener {
      sharedViewModel.sendCommand(HudCommand.SaveMedia)
    }

    setFragmentResultListener(MultiselectForwardFragment.RESULT_SELECTION) { _, bundle ->
      val recipientIds: List<RecipientId> = requireNotNull(bundle.getParcelableArrayList(MultiselectForwardFragment.RESULT_SELECTION_RECIPIENTS))
      performSend(recipientIds)
    }

    sendButton.setOnClickListener {
      if (sharedViewModel.isContactSelectionRequired) {
        val args = MultiselectForwardFragmentArgs(false, title = R.string.MediaReviewFragment__send_to)
        MultiselectForwardFragment.show(parentFragmentManager, args)
      } else {
        performSend()
      }
    }

    addMediaButton.setOnClickListener {
      launchGallery()
    }

    viewOnceButton.setOnClickListener {
      sharedViewModel.incrementViewOnceState()
    }

    addMessageButton.setOnClickListener {
      AddMessageDialogFragment.show(parentFragmentManager, sharedViewModel.state.value?.message)
    }

    addMessageEntry.setOnClickListener {
      AddMessageDialogFragment.show(parentFragmentManager, sharedViewModel.state.value?.message)
    }

    if (sharedViewModel.isReply) {
      addMessageButton.setText(R.string.MediaReviewFragment__add_a_reply)
    }

    pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
      override fun onPageSelected(position: Int) {
        sharedViewModel.setFocusedMedia(position)
      }
    })

    val selectionAdapter = MappingAdapter()
    MediaReviewAddItem.register(selectionAdapter) {
      launchGallery()
    }
    MediaReviewSelectedItem.register(selectionAdapter) { media, isSelected ->
      if (isSelected) {
        sharedViewModel.removeMedia(media)
      } else {
        sharedViewModel.setFocusedMedia(media)
      }
    }
    selectionRecycler.adapter = selectionAdapter

    sharedViewModel.state.observe(viewLifecycleOwner) { state ->
      pagerAdapter.submitMedia(state.selectedMedia)

      selectionAdapter.submitList(
        state.selectedMedia.map { MediaReviewSelectedItem.Model(it, state.focusedMedia == it) } + MediaReviewAddItem.Model
      )

      presentPager(state)
      presentAddMessageEntry(state.message)
      presentImageQualityToggle(state.quality)

      viewOnceButton.displayedChild = if (state.viewOnceToggleState == MediaSelectionState.ViewOnceToggleState.ONCE) 1 else 0
      sendButton.isEnabled = !state.isSent && state.selectedMedia.isNotEmpty()

      computeViewStateAndAnimate(state)
    }

    requireActivity().onBackPressedDispatcher.addCallback(
      viewLifecycleOwner,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          callback.onPopFromReview()
        }
      }
    )
  }

  override fun onResume() {
    super.onResume()
    sharedViewModel.kick()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  override fun onDestroyView() {
    disposables?.dispose()
    super.onDestroyView()
  }

  private fun launchGallery() {
    requestPermissionsForGallery {
      navigator.goToGallery(requireView())
    }
  }

  private fun performSend(selection: List<RecipientId> = listOf()) {
    sharedViewModel
      .send(selection)
      .subscribe(
        { result -> callback.onSentWithResult(result) },
        { error -> callback.onSendError(error) },
        { callback.onSentWithoutResult() }
      )
  }

  private fun presentAddMessageEntry(message: CharSequence?) {
    addMessageEntry.text = message
  }

  private fun presentImageQualityToggle(quality: SentMediaQuality) {
    qualityButton.setImageResource(
      when (quality) {
        SentMediaQuality.STANDARD -> R.drawable.ic_sq_36
        SentMediaQuality.HIGH -> R.drawable.ic_hq_36
      }
    )
  }

  private fun presentPager(state: MediaSelectionState) {
    pager.isUserInputEnabled = state.isTouchEnabled

    val indexOfSelectedItem = state.selectedMedia.indexOf(state.focusedMedia)

    if (pager.currentItem == indexOfSelectedItem) {
      return
    }

    if (indexOfSelectedItem != -1) {
      pager.setCurrentItem(indexOfSelectedItem, false)
    } else {
      pager.setCurrentItem(0, false)
    }
  }

  private fun computeViewStateAndAnimate(state: MediaSelectionState) {
    this.animatorSet?.cancel()

    val animators = mutableListOf<Animator>()

    animators.addAll(computeAddMessageAnimators(state))
    animators.addAll(computeViewOnceButtonAnimators(state))
    animators.addAll(computeAddMediaButtonsAnimators(state))
    animators.addAll(computeSendAndSaveButtonAnimators(state))
    animators.addAll(computeQualityButtonAnimators(state))
    animators.addAll(computeCropAndRotateButtonAnimators(state))
    animators.addAll(computeDrawToolButtonAnimators(state))
    animators.addAll(computeRecipientDisplayAnimators(state))
    animators.addAll(computeControlsShadeAnimators(state))

    val animatorSet = AnimatorSet()
    animatorSet.playTogether(animators)
    animatorSet.start()

    this.animatorSet = animatorSet
  }

  private fun computeControlsShadeAnimators(state: MediaSelectionState): List<Animator> {
    return if (state.isTouchEnabled) {
      listOf(MediaReviewAnimatorController.getFadeInAnimator(controlsShade))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(controlsShade))
    }
  }

  private fun computeAddMessageAnimators(state: MediaSelectionState): List<Animator> {
    return when {
      !state.isTouchEnabled -> {
        listOf(
          MediaReviewAnimatorController.getFadeOutAnimator(viewOnceMessage),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageButton),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageEntry)
        )
      }
      state.viewOnceToggleState == MediaSelectionState.ViewOnceToggleState.ONCE -> {
        listOf(
          MediaReviewAnimatorController.getFadeInAnimator(viewOnceMessage),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageButton),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageEntry)
        )
      }
      state.message.isNullOrEmpty() -> {
        listOf(
          MediaReviewAnimatorController.getFadeOutAnimator(viewOnceMessage),
          MediaReviewAnimatorController.getFadeInAnimator(addMessageButton),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageEntry)
        )
      }
      else -> {
        listOf(
          MediaReviewAnimatorController.getFadeOutAnimator(viewOnceMessage),
          MediaReviewAnimatorController.getFadeInAnimator(addMessageEntry),
          MediaReviewAnimatorController.getFadeOutAnimator(addMessageButton)
        )
      }
    }
  }

  private fun computeViewOnceButtonAnimators(state: MediaSelectionState): List<Animator> {
    return if (state.isTouchEnabled && state.selectedMedia.size == 1) {
      listOf(MediaReviewAnimatorController.getFadeInAnimator(viewOnceButton))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(viewOnceButton))
    }
  }

  private fun computeAddMediaButtonsAnimators(state: MediaSelectionState): List<Animator> {
    return when {
      !state.isTouchEnabled || state.viewOnceToggleState == MediaSelectionState.ViewOnceToggleState.ONCE -> {
        listOf(
          MediaReviewAnimatorController.getFadeOutAnimator(addMediaButton),
          MediaReviewAnimatorController.getFadeOutAnimator(selectionRecycler)
        )
      }
      state.selectedMedia.size > 1 -> {
        listOf(
          MediaReviewAnimatorController.getFadeOutAnimator(addMediaButton),
          MediaReviewAnimatorController.getFadeInAnimator(selectionRecycler)
        )
      }
      else -> {
        listOf(
          MediaReviewAnimatorController.getFadeInAnimator(addMediaButton),
          MediaReviewAnimatorController.getFadeOutAnimator(selectionRecycler)
        )
      }
    }
  }

  private fun computeSendAndSaveButtonAnimators(state: MediaSelectionState): List<Animator> {

    val slideIn = listOf(
      MediaReviewAnimatorController.getSlideInAnimator(sendButton),
      MediaReviewAnimatorController.getSlideInAnimator(saveButton)
    )

    return slideIn + if (state.isTouchEnabled) {
      listOf(
        MediaReviewAnimatorController.getFadeInAnimator(sendButton),
        MediaReviewAnimatorController.getFadeInAnimator(saveButton)
      )
    } else {
      listOf(
        MediaReviewAnimatorController.getFadeOutAnimator(sendButton),
        MediaReviewAnimatorController.getFadeOutAnimator(saveButton)
      )
    }
  }

  private fun computeQualityButtonAnimators(state: MediaSelectionState): List<Animator> {
    val slide = listOf(MediaReviewAnimatorController.getSlideInAnimator(qualityButton))

    return slide + if (state.isTouchEnabled && state.selectedMedia.any { MediaUtil.isImageType(it.mimeType) }) {
      listOf(MediaReviewAnimatorController.getFadeInAnimator(qualityButton))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(qualityButton))
    }
  }

  private fun computeCropAndRotateButtonAnimators(state: MediaSelectionState): List<Animator> {
    val slide = listOf(MediaReviewAnimatorController.getSlideInAnimator(cropAndRotateButton))

    return slide + if (state.isTouchEnabled && MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
      listOf(MediaReviewAnimatorController.getFadeInAnimator(cropAndRotateButton))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(cropAndRotateButton))
    }
  }

  private fun computeDrawToolButtonAnimators(state: MediaSelectionState): List<Animator> {
    val slide = listOf(MediaReviewAnimatorController.getSlideInAnimator(drawToolButton))

    return slide + if (state.isTouchEnabled && MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
      listOf(MediaReviewAnimatorController.getFadeInAnimator(drawToolButton))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(drawToolButton))
    }
  }

  private fun computeRecipientDisplayAnimators(state: MediaSelectionState): List<Animator> {
    return if (state.isTouchEnabled && state.recipient != null) {
      recipientDisplay.text = state.recipient.getDisplayName(requireContext())
      listOf(MediaReviewAnimatorController.getFadeInAnimator(recipientDisplay))
    } else {
      listOf(MediaReviewAnimatorController.getFadeOutAnimator(recipientDisplay))
    }
  }

  interface Callback {
    fun onSentWithResult(mediaSendActivityResult: MediaSendActivityResult)
    fun onSentWithoutResult()
    fun onSendError(error: Throwable)
    fun onPopFromReview()
  }
}
