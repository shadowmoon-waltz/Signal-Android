package org.thoughtcrime.securesms.stories.viewer

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveDataReactiveStreams
import androidx.viewpager2.widget.ViewPager2
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.blurhash.BlurHash
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.StoryTextPostModel
import org.thoughtcrime.securesms.stories.viewer.page.StoryViewerPageFragment

/**
 * Fragment which manages a vertical pager fragment of stories.
 */
class StoryViewerFragment : Fragment(R.layout.stories_viewer_fragment), StoryViewerPageFragment.Callback {

  private val onPageChanged = OnPageChanged()

  private lateinit var storyPager: ViewPager2

  private val viewModel: StoryViewerViewModel by viewModels(
    factoryProducer = {
      StoryViewerViewModel.Factory(storyRecipientId, onlyIncludeHiddenStories, storyThumbTextModel, storyThumbUri, storuThumbBlur, recipientIds, StoryViewerRepository())
    }
  )

  private val storyRecipientId: RecipientId
    get() = requireArguments().getParcelable(ARG_START_RECIPIENT_ID)!!

  private val storyId: Long
    get() = requireArguments().getLong(ARG_START_STORY_ID, -1L)

  private val onlyIncludeHiddenStories: Boolean
    get() = requireArguments().getBoolean(ARG_HIDDEN_STORIES)

  private val storyThumbTextModel: StoryTextPostModel?
    get() = requireArguments().getParcelable(ARG_CROSSFADE_TEXT_MODEL)

  private val storyThumbUri: Uri?
    get() = requireArguments().getParcelable(ARG_CROSSFADE_IMAGE_URI)

  private val storuThumbBlur: BlurHash?
    get() = requireArguments().getString(ARG_CROSSFADE_IMAGE_BLUR)?.let { BlurHash.parseOrNull(it) }

  private val recipientIds: List<RecipientId>
    get() = requireArguments().getParcelableArrayList(ARG_RECIPIENT_IDS)!!

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    storyPager = view.findViewById(R.id.story_item_pager)

    val adapter = StoryViewerPagerAdapter(this, storyId)
    storyPager.adapter = adapter

    viewModel.isChildScrolling.observe(viewLifecycleOwner) {
      storyPager.isUserInputEnabled = !it
    }

    LiveDataReactiveStreams.fromPublisher(viewModel.state).observe(viewLifecycleOwner) { state ->
      adapter.setPages(state.pages)
      if (state.pages.isNotEmpty() && storyPager.currentItem != state.page) {
        storyPager.setCurrentItem(state.page, state.previousPage > -1)

        if (state.page >= state.pages.size) {
          requireActivity().onBackPressed()
        }
      }

      if (state.loadState.isReady()) {
        requireActivity().supportStartPostponedEnterTransition()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.setIsScrolling(false)
    storyPager.registerOnPageChangeCallback(onPageChanged)
  }

  override fun onPause() {
    super.onPause()
    viewModel.setIsScrolling(false)
    storyPager.unregisterOnPageChangeCallback(onPageChanged)
  }

  override fun onGoToPreviousStory(recipientId: RecipientId) {
    viewModel.onGoToPrevious(recipientId)
  }

  override fun onFinishedPosts(recipientId: RecipientId) {
    viewModel.onGoToNext(recipientId)
  }

  override fun onStoryHidden(recipientId: RecipientId) {
    viewModel.onRecipientHidden()
  }

  inner class OnPageChanged : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
      viewModel.setSelectedPage(position)
    }

    override fun onPageScrollStateChanged(state: Int) {
      viewModel.setIsScrolling(state == ViewPager2.SCROLL_STATE_DRAGGING)
    }
  }

  companion object {
    private const val ARG_START_RECIPIENT_ID = "start.recipient.id"
    private const val ARG_START_STORY_ID = "start.story.id"
    private const val ARG_HIDDEN_STORIES = "hidden_stories"
    private const val ARG_CROSSFADE_TEXT_MODEL = "crossfade.text.model"
    private const val ARG_CROSSFADE_IMAGE_URI = "crossfade.image.uri"
    private const val ARG_CROSSFADE_IMAGE_BLUR = "crossfade.image.blur"
    private const val ARG_RECIPIENT_IDS = "start.recipient.ids"

    fun create(
      storyRecipientId: RecipientId,
      storyId: Long,
      onlyIncludeHiddenStories: Boolean,
      storyThumbTextModel: StoryTextPostModel? = null,
      storyThumbUri: Uri? = null,
      storyThumbBlur: String? = null,
      recipientIds: List<RecipientId> = emptyList()
    ): Fragment {
      return StoryViewerFragment().apply {
        arguments = Bundle().apply {
          putParcelable(ARG_START_RECIPIENT_ID, storyRecipientId)
          putLong(ARG_START_STORY_ID, storyId)
          putBoolean(ARG_HIDDEN_STORIES, onlyIncludeHiddenStories)
          putParcelable(ARG_CROSSFADE_TEXT_MODEL, storyThumbTextModel)
          putParcelable(ARG_CROSSFADE_IMAGE_URI, storyThumbUri)
          putString(ARG_CROSSFADE_IMAGE_BLUR, storyThumbBlur)
          putParcelableArrayList(ARG_RECIPIENT_IDS, ArrayList(recipientIds))
        }
      }
    }
  }
}
