package com.example.btplayer

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** Квадрат по высоте родителя — обложка всегда крупная на любом экране 9–10". */
class SquareImageView @JvmOverloads constructor(
    c: Context, a: AttributeSet? = null, s: Int = 0
) : ImageView(c, a, s) {
    override fun onMeasure(w: Int, h: Int) {
        val side = MeasureSpec.getSize(h)
        setMeasuredDimension(side, side)
    }
}
