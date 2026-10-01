package home.lampremote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Dark palette of the DiWA app. */
object Palette {
  const val CARD = 0xFF1D1D1A.toInt()
  const val TEXT = 0xFFF1F1EA.toInt()
  const val MUTED = 0xFFA3A398.toInt()
  const val BORDER = 0xFF303029.toInt()
  const val ACCENT = 0xFFCFC93F.toInt()
  const val DANGER = 0xFFF0705F.toInt()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.text(value: String, size: Float, color: Int = Palette.TEXT, bold: Boolean = false): TextView =
  TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
  }

/** A pressable card with an icon above a label; [setActive] draws the accent outline. */
class Tile(context: Context, icon: Int, label: String, iconDp: Int, onClick: () -> Unit) {
  private val shape = GradientDrawable().apply {
    cornerRadius = context.dp(16).toFloat()
    setColor(Palette.CARD)
  }
  private val image = ImageView(context).apply { setImageResource(icon) }
  private val caption = context.text(label, 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
  private val thin = context.dp(1)
  private val thick = context.dp(2)

  val view: View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, shape)
    setPadding(context.dp(4), context.dp(12), context.dp(4), context.dp(10))
    addView(image, LinearLayout.LayoutParams(context.dp(iconDp), context.dp(iconDp)))
    addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
    isClickable = true
    setOnClickListener { onClick() }
  }

  init {
    setActive(false)
  }

  fun setActive(active: Boolean) {
    shape.setStroke(if (active) thick else thin, if (active) Palette.ACCENT else Palette.BORDER)
    image.setColorFilter(if (active) Palette.ACCENT else Palette.TEXT)
    caption.setTextColor(if (active) Palette.ACCENT else Palette.MUTED)
  }
}
