package com.veycad.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

/** A bounded central dialog: only the choices scroll, never the actions. */
internal object StylePickerDialog {
    fun show(activity: AppCompatActivity, styles: List<MontageStyleCatalog.Style>, selectedId: String,
        onApply: (MontageStyleCatalog.Style) -> Unit): Dialog {
        val selection = StylePickerSelection(styles, selectedId)
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun colour(id: Int) = activity.getColor(id)
        fun surface(selected: Boolean = false) = GradientDrawable().apply {
            setColor(colour(R.color.surface))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1).coerceAtLeast(1), colour(if(selected) R.color.neon else R.color.stroke))
        }
        fun label(text: String, size: Float, color: Int) = TextView(activity).apply {
            this.text = text; textSize = size; setTextColor(colour(color))
        }
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(24),dp(20),dp(16))
            background = surface()
        }
        val title = label("Выбрать стиль",24f,R.color.text_primary).apply {
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
        }
        root.addView(title)
        root.addView(label("Выбери характер монтажа",14f,R.color.text_secondary),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(20) })
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        val list = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list,ViewGroup.LayoutParams(-1,-2))
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        val rows = mutableListOf<Pair<View,TextView>>()
        fun refresh() = rows.forEachIndexed { index,(row,radio) ->
            val chosen = styles[index].id == selection.selected.id
            row.background = surface(chosen)
            row.isSelected = chosen
            radio.text = if (!styles[index].available && styles[index].recipe == MontageStyleCatalog.Recipe.SIGMA)
                "🔒" else if(chosen) "●" else "○"
            radio.setTextColor(colour(if(chosen) R.color.neon else R.color.text_muted))
        }
        styles.forEach { style ->
            val row = LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(14),dp(16),dp(14),dp(16))
                minimumHeight=dp(96)
                isFocusable=style.available; isClickable=style.available
                contentDescription="${style.title}. ${style.description}" + if(style.available) "" else ". ${style.unavailableLabel}"
            }
            val radio=label("○",26f,R.color.text_muted).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(radio,LinearLayout.LayoutParams(dp(36),-2))
            val copy=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            copy.addView(label(style.title,18f,if(style.available) R.color.text_primary else R.color.text_muted))
            copy.addView(label(style.description.removePrefix("В разработке · "),14f,R.color.text_secondary),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
            if(!style.available) copy.addView(label(style.unavailableLabel,12f,R.color.text_muted),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            rows += row to radio
            row.setOnClickListener {
                if(selection.select(style.id)) {
                    refresh()
                    LocalDiagnostics.record(activity,"style_picker_pending",mapOf("style" to style.id))
                }
            }
            if(!style.available) { row.isClickable=false; row.isEnabled=false }
            list.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        }
        refresh()
        val actions=LinearLayout(activity).apply { gravity=Gravity.END; orientation=LinearLayout.HORIZONTAL }
        fun button(text: String, primary: Boolean) = AppCompatButton(activity).apply {
            this.text=text; isAllCaps=false; textSize=16f; minHeight=dp(52)
            setPadding(dp(14),dp(8),dp(14),dp(8))
            setTextColor(colour(if(primary) R.color.black else R.color.text_secondary))
            background=GradientDrawable().apply {
                setColor(if(primary) colour(R.color.neon) else Color.TRANSPARENT)
                cornerRadius=dp(16).toFloat()
            }
            androidx.core.view.ViewCompat.setBackgroundTintList(this,null)
        }
        actions.addView(button("Отмена",false).apply { setOnClickListener { dialog.cancel() } },
            LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(button("Применить",true).apply { setOnClickListener {
            onApply(selection.selected); dialog.dismiss()
        } },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.72f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
        }
        dialog.setOnShowListener {
            val metrics=activity.resources.displayMetrics
            val width=minOf(metrics.widthPixels-dp(32),dp(560))
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val chrome=root.measuredHeight-scroll.measuredHeight
            val available=(metrics.heightPixels*.8f).toInt()-chrome
            scroll.layoutParams=scroll.layoutParams.apply { height=minOf(scroll.measuredHeight,available.coerceAtLeast(dp(48))) }
            dialog.window?.setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }
}

internal class StylePickerSelection(private val styles: List<MontageStyleCatalog.Style>, selectedId: String) {
    var selected = styles.firstOrNull { it.id==selectedId && it.available }
        ?: styles.first { it.available }
        private set
    fun select(id: String): Boolean {
        val choice=styles.firstOrNull { it.id==id && it.available } ?: return false
        selected=choice
        return true
    }
}
