package com.yaya.praline

import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * Drag a row up and down a vertical list by its handle (1.4). The row follows the finger; when it passes the
 * middle of a neighbour the two swap places, in the list and in the data ([moved] from, to). Plain framework
 * touch handling: no RecyclerView is available to this build.
 */
object Reorder {
    fun attach(handle: View, row: View, list: LinearLayout, moved: (Int, Int) -> Unit) {
        var lastY = 0f
        handle.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY = e.rawY
                        list.parent?.requestDisallowInterceptTouchEvent(true)
                        row.elevation = row.context.dpf(6f); row.alpha = 0.9f
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        row.translationY += e.rawY - lastY
                        lastY = e.rawY
                        step(row, list, moved)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        row.animate().translationY(0f).setDuration(120).start()
                        row.elevation = 0f; row.alpha = 1f
                        list.parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return false
            }
        })
    }

    /** Swaps [row] with the neighbour whose middle it crossed, keeping it under the finger. */
    fun step(row: View, list: LinearLayout, moved: (Int, Int) -> Unit) {
        // wait for the last swap to be laid out before comparing positions again
        if (row.isLayoutRequested || list.isLayoutRequested) return
        val i = list.indexOfChild(row)
        if (i < 0) return
        val mid = row.top + row.translationY + row.height / 2f
        if (i > 0) {
            val up = list.getChildAt(i - 1)
            if (mid < up.top + up.height / 2f) { swap(row, list, i, i - 1, moved); return }
        }
        if (i < list.childCount - 1) {
            val dn = list.getChildAt(i + 1)
            if (mid > dn.top + dn.height / 2f) swap(row, list, i, i + 1, moved)
        }
    }

    private fun swap(row: View, list: LinearLayout, from: Int, to: Int, moved: (Int, Int) -> Unit) {
        val other = list.getChildAt(to)
        val shift = if (to < from) -other.height.toFloat() - margins(other) else other.height.toFloat() + margins(other)
        list.removeViewAt(from)
        list.addView(row, to)
        // the row now sits where the neighbour was: take that jump out of the drag offset
        row.translationY -= shift
        moved(from, to)
    }

    private fun margins(v: View): Float {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return 0f
        return (lp.topMargin + lp.bottomMargin).toFloat()
    }
}
