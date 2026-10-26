package com.olegskal.mushroom.ui

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

object UiUtils {

    fun createStandardItemParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 4, 0, 4)
        }
    }

    fun createDarkDialogContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#252525"))
        }
    }

    fun createDialogDivider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                setMargins(0, 10, 0, 10)
            }
        }
    }

    fun createStyledButton(
        context: Context,
        buttonText: String,
        params: LinearLayout.LayoutParams? = null,
        icon: android.graphics.drawable.Drawable? = null,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            textSize = 14f
            gravity = Gravity.CENTER
            if (icon != null) {
                val density = resources.displayMetrics.density
                val sz = (18 * density).toInt()
                icon.setBounds(0, 0, sz, sz)
                val ssb = SpannableStringBuilder("  ").append(buttonText)
                ssb.setSpan(CenteredImageSpan(icon), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = ssb
            } else {
                text = buttonText
            }
            params?.let { layoutParams = it }
            setOnClickListener { onClick() }
        }
    }
}
