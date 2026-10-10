package com.veycad.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

/** A bounded central dialog: only the choices scroll, never the actions. */
internal object StylePickerDialog {
    fun show(activity: AppCompatActivity, styles: List<MontageStyleCatalog.Style>, selectedId: String,
        onApply: (MontageStyleCatalog.Style) -> Unit): Dialog {
        val selection = StylePickerSelection(styles, selectedId)
        val displayStyles = styles.sortedBy { !it.available }
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
        root.addView(label("Смотри примеры и выбирай настроение\nБез звука · каждый пример 3 секунды",14f,R.color.text_secondary),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(20) })
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        val list = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list,ViewGroup.LayoutParams(-1,-2))
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        val rows = mutableListOf<Triple<MontageStyleCatalog.Style,View,TextView>>()
        val previews = mutableListOf<StylePreviewPlayback.Card>()
        var playback: StylePreviewPlayback? = null
        fun refresh() = rows.forEach { (style,row,radio) ->
            val chosen = style.id == selection.selected.id
            row.background = surface(chosen)
            row.isSelected = chosen
            radio.text = if (!style.available && style.recipe == MontageStyleCatalog.Recipe.SIGMA)
                "🔒" else if(chosen) "●" else "○"
            radio.setTextColor(colour(if(chosen) R.color.neon else R.color.text_muted))
        }
        displayStyles.forEach { style ->
            val presentation = MontageStylePresentation.forStyle(style)
            val row = LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(12),dp(12),dp(12),dp(12))
                minimumHeight=dp(96)
                isFocusable=style.available; isClickable=style.available
                contentDescription="${presentation.title}. ${presentation.subtitle}" +
                    if(style.available) ". ${presentation.sourceHint}" else ". ${style.unavailableLabel}"
            }
            StylePreviewAssets.forStyle(style)?.let { asset ->
                val preview = FrameLayout(activity).apply {
                    tag = "style_preview:${style.id}"
                    contentDescription = "Смотреть пример: ${presentation.title}. 3 секунды, без звука"
                    isClickable = true; isFocusable = true
                    background = GradientDrawable().apply {
                        setColor(Color.BLACK); cornerRadius = dp(12).toFloat()
                    }
                    clipToOutline = true
                    setOnClickListener { playback?.request(style.id) }
                }
                val poster = ImageView(activity).apply {
                    setImageResource(asset.poster)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                preview.addView(poster, FrameLayout.LayoutParams(-1,-1))
                val status = TextView(activity).apply {
                    text = "▶ Пример · 3 с"; textSize = 11f; setTextColor(Color.WHITE)
                    setPadding(dp(6),dp(7),dp(6),dp(7))
                    setBackgroundColor(0xCC000000.toInt())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                preview.addView(status, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
                previews += StylePreviewPlayback.Card(style.id,asset,preview,poster,status)
                row.addView(preview,LinearLayout.LayoutParams(dp(96),dp(152)).apply { marginEnd=dp(10) })
            }
            val radio=label("○",26f,R.color.text_muted).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val copy=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            copy.addView(label(presentation.title,18f,if(style.available) R.color.text_primary else R.color.text_muted).apply {
                setTypeface(typeface,android.graphics.Typeface.BOLD)
            })
            if (style.available) copy.addView(label(presentation.profileName,12f,R.color.text_muted),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
            copy.addView(label(presentation.subtitle.removePrefix("В разработке · "),14f,R.color.text_secondary),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
            if(style.available) copy.addView(label(presentation.sourceHint,12f,R.color.text_muted),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            if(!style.available) copy.addView(label(style.unavailableLabel,12f,R.color.text_muted),
                LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(radio,LinearLayout.LayoutParams(dp(24),-2).apply { marginStart=dp(6) })
            rows += Triple(style,row,radio)
            row.setOnClickListener {
                if(selection.select(style.id)) {
                    refresh()
                    playback?.update()
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
        playback = StylePreviewPlayback(activity,scroll,previews) { selection.selected.id }
        dialog.setOnDismissListener { playback?.close() }
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
            scroll.post {
                if (dialog.isShowing) {
                    scroll.scrollTo(0,rows.firstOrNull { it.first.id == selection.selected.id }?.second?.top ?: 0)
                    playback?.update()
                }
            }
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
