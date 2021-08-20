package org.thoughtcrime.securesms.conversation.mutiselect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.SimpleColorFilter
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationAdapter
import org.thoughtcrime.securesms.util.Projection
import org.thoughtcrime.securesms.util.SetUtil
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import java.lang.Integer.max

/**
 * Decoration which renders the background shade and selection bubble for a {@link Multiselectable} item.
 */
class MultiselectItemDecoration(
  context: Context,
  private val chatWallpaperProvider: () -> ChatWallpaper?,
  private val selectedAnimationProgressProvider: (MultiselectPart) -> Float,
  private val isInitialAnimation: () -> Boolean
) : RecyclerView.ItemDecoration(), DefaultLifecycleObserver {

  private val path = Path()
  private val rect = Rect()
  private val gutter = ViewUtil.dpToPx(48)
  private val paddingStart = ViewUtil.dpToPx(17)
  private val circleRadius = ViewUtil.dpToPx(11)
  private val checkDrawable = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_check_circle_solid_24)).apply {
    setBounds(0, 0, circleRadius * 2, circleRadius * 2)
  }
  private val photoCircleRadius = ViewUtil.dpToPx(12)
  private val photoCirclePaddingStart = ViewUtil.dpToPx(16)

  private val transparentBlack20 = ContextCompat.getColor(context, R.color.transparent_black_20)
  private val transparentWhite20 = ContextCompat.getColor(context, R.color.transparent_white_20)
  private val transparentWhite60 = ContextCompat.getColor(context, R.color.transparent_white_60)
  private val ultramarine30 = ContextCompat.getColor(context, R.color.core_ultramarine_33)
  private val ultramarine = ContextCompat.getColor(context, R.color.signal_accent_primary)

  private var checkedBitmap: Bitmap? = null

  override fun onCreate(owner: LifecycleOwner) {
    val bitmap = Bitmap.createBitmap(circleRadius * 2, circleRadius * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    checkDrawable.draw(canvas)
    checkedBitmap = bitmap
  }

  override fun onDestroy(owner: LifecycleOwner) {
    checkedBitmap?.recycle()
    checkedBitmap = null
  }

  private val unselectedPaint = Paint().apply {
    isAntiAlias = true
    strokeWidth = 1.5f
    style = Paint.Style.STROKE
  }

  private val shadePaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
  }

  private val photoCirclePaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
    color = transparentBlack20
  }

  private val checkPaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
  }

  override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
    outRect.setEmpty()
    updateChildOffsets(parent, view)
  }

  /**
   * Draws the background shade.
   */
  @Suppress("DEPRECATION")
  override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    val adapter = parent.adapter as ConversationAdapter

    if (adapter.selectedItems.isEmpty()) {
      return
    }

    shadePaint.color = when {
      chatWallpaperProvider() != null -> transparentBlack20
      ThemeUtil.isDarkTheme(parent.context) -> transparentWhite20
      else -> ultramarine30
    }

    parent.children.filterIsInstance(Multiselectable::class.java).forEach { child ->
      updateChildOffsets(parent, child as View)

      val parts: MultiselectCollection = child.conversationMessage.multiselectCollection

      val projections: List<Projection> = child.colorizerProjections
      path.reset()
      projections.forEach { it.applyToPath(path) }

      canvas.save()
      canvas.clipPath(path, Region.Op.DIFFERENCE)

      val selectedParts: Set<MultiselectPart> = SetUtil.intersection(parts.toSet(), adapter.selectedItems)

      if (selectedParts.isNotEmpty()) {
        val selectedPart: MultiselectPart = selectedParts.first()
        val shadeAll = selectedParts.size == parts.size || (selectedPart is MultiselectPart.Text && child.hasNonSelectableMedia())

        if (shadeAll) {
          rect.set(0, child.top, child.right, child.bottom)
        } else {
          rect.set(0, child.getTopBoundaryOfMultiselectPart(selectedPart), parent.right, child.getBottomBoundaryOfMultiselectPart(selectedPart))
        }

        canvas.drawRect(rect, shadePaint)
      }

      canvas.restore()
    }
  }

  /**
   * Draws the selected check or empty circle.
   */
  override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    val adapter = parent.adapter as ConversationAdapter
    if (adapter.selectedItems.isEmpty()) {
      return
    }

    val drawCircleBehindSelector = chatWallpaperProvider()?.isPhoto == true
    val multiselectChildren: Sequence<Multiselectable> = parent.children.filterIsInstance(Multiselectable::class.java)

    val isDarkTheme = ThemeUtil.isDarkTheme(parent.context)

    unselectedPaint.color = when {
      chatWallpaperProvider()?.isPhoto == true -> Color.WHITE
      chatWallpaperProvider() != null || isDarkTheme -> transparentWhite60
      else -> transparentBlack20
    }

    if (chatWallpaperProvider() == null && !isDarkTheme) {
      checkPaint.colorFilter = SimpleColorFilter(ultramarine)
    } else {
      checkPaint.colorFilter = null
    }

    multiselectChildren.forEach { child ->
      val parts: MultiselectCollection = child.conversationMessage.multiselectCollection

      parts.toSet().forEach {
        val topBoundary = child.getTopBoundaryOfMultiselectPart(it)
        val bottomBoundary = child.getBottomBoundaryOfMultiselectPart(it)
        if (drawCircleBehindSelector) {
          drawPhotoCircle(canvas, parent, topBoundary, bottomBoundary)
        }

        val alphaProgress = selectedAnimationProgressProvider(it)
        if (adapter.selectedItems.contains(it)) {
          drawUnselectedCircle(canvas, parent, topBoundary, bottomBoundary, 1f - alphaProgress)
          drawSelectedCircle(canvas, parent, topBoundary, bottomBoundary, alphaProgress)
        } else {
          drawUnselectedCircle(canvas, parent, topBoundary, bottomBoundary, alphaProgress)
          if (!isInitialAnimation()) {
            drawSelectedCircle(canvas, parent, topBoundary, bottomBoundary, 1f - alphaProgress)
          }
        }
      }
    }
  }

  /**
   * Draws an extra circle behind the selection circle. This is to make it easier to see and
   * is specifically for when a photo wallpaper is being used.
   */
  private fun drawPhotoCircle(canvas: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int) {
    val centerX: Float = if (ViewUtil.isLtr(parent)) {
      photoCirclePaddingStart + photoCircleRadius
    } else {
      parent.right - photoCircleRadius - photoCirclePaddingStart
    }.toFloat()

    val centerY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2

    canvas.drawCircle(centerX, centerY, photoCircleRadius.toFloat(), photoCirclePaint)
  }

  /**
   * Draws the checkmark for selected content
   */
  private fun drawSelectedCircle(canvas: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int, alphaProgress: Float) {
    val topX: Float = if (ViewUtil.isLtr(parent)) {
      paddingStart
    } else {
      parent.right - paddingStart - circleRadius * 2
    }.toFloat()

    val topY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2 - circleRadius
    val bitmap = checkedBitmap

    val alpha = checkPaint.alpha
    checkPaint.alpha = (alpha * alphaProgress).toInt()

    if (bitmap != null) {
      canvas.drawBitmap(bitmap, topX, topY, checkPaint)
    }

    checkPaint.alpha = alpha
  }

  /**
   * Draws the empty circle for unselected content
   */
  private fun drawUnselectedCircle(c: Canvas, parent: RecyclerView, topBoundary: Int, bottomBoundary: Int, alphaProgress: Float) {
    val centerX: Float = if (ViewUtil.isLtr(parent)) {
      paddingStart + circleRadius
    } else {
      parent.right - circleRadius - paddingStart
    }.toFloat()

    val alpha = unselectedPaint.alpha
    unselectedPaint.alpha = (alpha * alphaProgress).toInt()
    val centerY: Float = topBoundary + (bottomBoundary - topBoundary).toFloat() / 2

    c.drawCircle(centerX, centerY, circleRadius.toFloat(), unselectedPaint)
    unselectedPaint.alpha = alpha
  }

  /**
   * Update the start-aligned gutter in which the checks display. This is called in onDraw to
   * ensure we don't hit situations where we try to set offsets before items are laid out, and
   * called in getItemOffsets to ensure the gutter goes away when multiselect mode ends.
   */
  private fun updateChildOffsets(parent: RecyclerView, child: View) {
    val adapter = parent.adapter as ConversationAdapter
    val isLtr = ViewUtil.isLtr(child)

    if (adapter.selectedItems.isNotEmpty() && child is Multiselectable) {
      val firstPart = child.conversationMessage.multiselectCollection.toSet().first()
      val target = child.getHorizontalTranslationTarget()

      if (target != null) {
        val start = if (isLtr) {
          target.left
        } else {
          parent.right - target.right
        }

        val translation: Float = if (isInitialAnimation()) {
          max(0, gutter - start) * selectedAnimationProgressProvider(firstPart)
        } else {
          max(0, gutter - start).toFloat()
        }

        child.translationX = if (isLtr) {
          translation
        } else {
          -translation
        }
      }
    } else if (child is Multiselectable) {
      child.translationX = 0f
    }
  }
}
