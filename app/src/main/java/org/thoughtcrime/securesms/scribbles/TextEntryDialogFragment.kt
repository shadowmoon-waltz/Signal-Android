package org.thoughtcrime.securesms.scribbles

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.fragment.app.FragmentManager
import com.airbnb.lottie.SimpleColorFilter
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.KeyboardEntryDialogFragment
import org.thoughtcrime.securesms.imageeditor.HiddenEditText
import org.thoughtcrime.securesms.imageeditor.model.EditorElement
import org.thoughtcrime.securesms.imageeditor.renderers.MultiLineTextRenderer
import org.thoughtcrime.securesms.keyboard.findListener
import org.thoughtcrime.securesms.scribbles.HSVColorSlider.getColor
import org.thoughtcrime.securesms.scribbles.HSVColorSlider.setUpForColor
import org.thoughtcrime.securesms.util.ViewUtil

class TextEntryDialogFragment : KeyboardEntryDialogFragment(R.layout.v2_media_image_editor_text_entry_fragment) {

  private lateinit var hiddenTextEntry: HiddenEditText
  private lateinit var controller: Controller

  private var colorIndicatorAlphaAnimator: Animator? = null

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    controller = requireNotNull(findListener())

    hiddenTextEntry = HiddenEditText(requireContext())
    (view as ViewGroup).addView(hiddenTextEntry)

    view.setOnClickListener {
      dismissAllowingStateLoss()
    }

    val element: EditorElement = requireNotNull(requireArguments().getParcelable("element"))
    val incognito = requireArguments().getBoolean("incognito")
    val selectAll = requireArguments().getBoolean("selectAll")

    hiddenTextEntry.setCurrentTextEditorElement(element)
    hiddenTextEntry.setIncognitoKeyboardEnabled(incognito)

    if (selectAll) {
      hiddenTextEntry.selectAll()
    }

    hiddenTextEntry.setOnEditOrSelectionChange { editorElement, textRenderer ->
      controller.zoomToFitText(editorElement, textRenderer)
    }

    hiddenTextEntry.setOnEndEdit {
      dismissAllowingStateLoss()
    }

    ViewUtil.focusAndShowKeyboard(hiddenTextEntry)

    val slider: AppCompatSeekBar = view.findViewById(R.id.image_editor_hud_draw_color_bar)
    val colorIndicator: ImageView = view.findViewById(R.id.image_editor_hud_color_indicator)
    slider.setUpForColor(
      Color.WHITE,
      {
        colorIndicator.drawable.colorFilter = SimpleColorFilter(slider.getColor())
        colorIndicator.translationX = (slider.thumb.bounds.left.toFloat() + ViewUtil.dpToPx(16))
        controller.onTextColorChange(slider.progress)
      },
      {
        colorIndicatorAlphaAnimator?.end()
        colorIndicatorAlphaAnimator = ObjectAnimator.ofFloat(colorIndicator, "alpha", colorIndicator.alpha, 1f)
        colorIndicatorAlphaAnimator?.duration = 150L
        colorIndicatorAlphaAnimator?.start()
      },
      {
        colorIndicatorAlphaAnimator?.end()
        colorIndicatorAlphaAnimator = ObjectAnimator.ofFloat(colorIndicator, "alpha", colorIndicator.alpha, 0f)
        colorIndicatorAlphaAnimator?.duration = 150L
        colorIndicatorAlphaAnimator?.start()
      }
    )

    slider.progress = requireArguments().getInt("color_index")
  }

  override fun onDismiss(dialog: DialogInterface) {
    controller.onTextEntryDialogDismissed(!hiddenTextEntry.text.isNullOrEmpty())
  }

  interface Controller {
    fun onTextEntryDialogDismissed(hasText: Boolean)
    fun zoomToFitText(editorElement: EditorElement, textRenderer: MultiLineTextRenderer)
    fun onTextColorChange(colorIndex: Int)
  }

  companion object {
    fun show(
      fragmentManager: FragmentManager,
      editorElement: EditorElement,
      isIncognitoEnabled: Boolean,
      selectAll: Boolean,
      colorIndex: Int
    ) {
      val args = Bundle().apply {
        putParcelable("element", editorElement)
        putBoolean("incognito", isIncognitoEnabled)
        putBoolean("selectAll", selectAll)
        putInt("color_index", colorIndex)
      }

      TextEntryDialogFragment().apply {
        arguments = args
        show(fragmentManager, "text_entry")
      }
    }
  }
}
