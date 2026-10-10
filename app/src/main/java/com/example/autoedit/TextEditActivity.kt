package com.veycad.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.UUID

class TextEditActivity:AppCompatActivity() {
    private lateinit var session:TextEditSession
    private lateinit var video:VideoView
    private lateinit var overlay:TextPreviewOverlay
    private lateinit var seek:SeekBar
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private lateinit var progress:ProgressBar
    private lateinit var cancel:Button
    private lateinit var export:Button
    private lateinit var choose:Button
    private lateinit var tabs:LinearLayout
    private var section=0
    private var captionPage=0
    private var preparedSource:String?=null
    private var previewOffsetMs=0
    private var shownProject:TextEditProject?=null
    private var shownBusy:Boolean?=null
    private val handler=Handler(Looper.getMainLooper())
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) session.import(uri)
    }
    private val ticker=object:Runnable {
        override fun run() {
            overlay.timeUs=previewTimeMs()*1000L
            overlay.invalidate()
            if(!seek.isPressed) seek.progress=previewTimeMs()
            handler.postDelayed(this,50)
        }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun label(value:String,size:Float=14f)=TextView(this).apply {
        text=value; textSize=size; setTextColor(getColor(R.color.text_primary)); setPadding(0,dp(8),0,dp(8))
    }
    private fun button(value:String,id:Int=View.NO_ID,action:()->Unit)=Button(this).apply {
        text=value; this.id=id; isAllCaps=false; setTextColor(getColor(R.color.text_primary))
        backgroundTintList=android.content.res.ColorStateList.valueOf(getColor(R.color.surface_high))
        setOnClickListener { action() }
        layoutParams=LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(6) }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        session=TextEditSession.get(this)
        section=savedInstanceState?.getInt("section") ?: 0
        val scroll=ScrollView(this).apply { setBackgroundColor(getColor(R.color.background)) }
        val root=column().apply { setPadding(dp(18),dp(12),dp(18),dp(24)) }
        // Keep system insets outside ScrollView: its rectangle scrolling uses the full
        // viewport height and can otherwise leave requested controls under bottom padding.
        val viewport=FrameLayout(this)
        scroll.addView(root)
        viewport.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        setContentView(viewport)
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        root.addView(button("‹  Назад") { finish() })
        root.addView(label(getString(R.string.text_editor_title),26f))
        root.addView(label(getString(R.string.text_source_help)))
        choose=button(getString(R.string.text_choose),R.id.textChooseButton) { picker.launch(arrayOf("video/*")) }
        root.addView(choose)
        val preview=FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video=VideoView(this).apply {
            id=R.id.textPreview
            // Async media layout must not pull the editor's ScrollView back to this preview.
            isFocusable=false
            isFocusableInTouchMode=false
        }
        overlay=TextPreviewOverlay(this)
        preview.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        preview.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(preview,LinearLayout.LayoutParams(-1,dp(300)).apply { topMargin=dp(12) })
        preview.setOnClickListener { if(video.isPlaying) video.pause() else video.start() }
        video.setOnPreparedListener { player ->
            player.isLooping=false
            val project=session.state.value?.project
            val originMs=((project?.sourceOriginUs ?: 0)/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            previewOffsetMs=if(project!=null && player.duration>=project.durationUs/1000+originMs-100) originMs else 0
            video.seekTo(previewOffsetMs+1)
        }
        video.setOnErrorListener { _,_,_ -> status.text="Не удалось открыть предпросмотр"; true }
        seek=SeekBar(this)
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) { if(fromUser) video.seekTo(value+previewOffsetMs) }
            override fun onStartTrackingTouch(bar:SeekBar?) { video.pause() }
            override fun onStopTrackingTouch(bar:SeekBar?) {}
        })
        root.addView(seek)
        tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf("Заголовок","Плашки","Субтитры").forEachIndexed { i,text ->
            tabs.addView(button(text) { section=i; showContent(session.state.value) }.apply {
                layoutParams=LinearLayout.LayoutParams(0,dp(52),1f); textSize=12f
            })
        }
        root.addView(tabs)
        content=column(); root.addView(content)
        status=label(""); root.addView(status)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        root.addView(progress)
        cancel=button("Отменить обработку",R.id.textCancelButton) { session.cancel() }; root.addView(cancel)
        export=button(getString(R.string.text_export),R.id.textExportButton) { video.pause(); session.export() }
        export.backgroundTintList=android.content.res.ColorStateList.valueOf(getColor(R.color.neon))
        export.setTextColor(getColor(R.color.white)); root.addView(export)
        session.state.observe(this) { state ->
            choose.isEnabled=!state.busy; export.isEnabled=!state.busy && state.project!=null
            progress.visibility=if(state.busy) View.VISIBLE else View.GONE; progress.progress=state.progress
            cancel.visibility=if(state.busy) View.VISIBLE else View.GONE; cancel.isEnabled=state.canCancel
            status.text=when { state.busy -> "${state.operation} · ${state.progress}%"; state.error!=null -> state.error
                state.entry!=null -> "Готово. Новый MP4 сохранён в «Мои эдиты»."; else -> "Черновик сохраняется автоматически" }
            state.project?.let { project ->
                overlay.project=project; overlay.invalidate()
                seek.max=(project.durationUs/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if(preparedSource!=project.sourcePath) { preparedSource=project.sourcePath; video.setVideoPath(project.sourcePath) }
            }
            if(shownProject !== state.project || shownBusy != state.busy) showContent(state)
        }
        val path=if(savedInstanceState==null) intent.getStringExtra(EXTRA_SOURCE)
            else savedInstanceState.getString("activeSource")
        if(path!=null) session.open(File(path)) else session.resume()
        if(path==null && savedInstanceState==null && session.state.value?.project==null && session.state.value?.busy!=true)
            picker.launch(arrayOf("video/*"))
    }
    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); video.pause(); super.onPause() }
    override fun onDestroy() { video.stopPlayback(); super.onDestroy() }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putInt("section",section); outState.putString("activeSource",session.state.value?.project?.sourcePath)
        super.onSaveInstanceState(outState)
    }
    private fun previewTimeMs()=(video.currentPosition-previewOffsetMs).coerceAtLeast(0)
    private fun showContent(state:TextEditSession.State?) {
        shownProject=state?.project; shownBusy=state?.busy
        content.removeAllViews()
        val p=state?.project ?: return
        for(i in 0 until tabs.childCount) tabs.getChildAt(i).alpha=if(i==section) 1f else .55f
        fun action(text:String,id:Int=View.NO_ID,block:()->Unit) { content.addView(button(text,id,block).apply { isEnabled=!state.busy }) }
        when(section) {
            0 -> {
                action(getString(R.string.text_hook),R.id.textHookButton) { editLayer(p.hook("Как сделать X"),false) }
                p.layers.filter { it.style.position==TextPosition.TOP }.forEach { l -> action(l.text) { editLayer(l,true) } }
            }
            1 -> {
                action(getString(R.string.text_plate),R.id.textPlateButton) {
                    val start=(previewTimeMs()*1000L).coerceIn(0,p.durationUs-1)
                    editLayer(TextLayer(UUID.randomUUID().toString(),"Описание",start,minOf(p.durationUs,start+3_000_000),TextStyle(position=TextPosition.CENTER)),false)
                }
                // All manual layers stay reachable even after moving a title to a different position.
                p.layers.forEach { l -> action("${seconds(l.startUs)}–${seconds(l.endUs)} · ${l.text}") { editLayer(l,true) } }
            }
            else -> {
                content.addView(label(getString(R.string.text_speech_help)))
                action("Язык · "+mapOf("auto" to "Авто","ru" to "Русский","en" to "English")[p.language]) {
                    AlertDialog.Builder(this).setTitle("Язык речи").setItems(arrayOf("Авто","Русский","English")) { _,which ->
                        session.update(p.copy(language=listOf("auto","ru","en")[which]))
                    }.show()
                }
                action(getString(R.string.text_recognize),R.id.textSpeechButton) {
                    video.pause()
                    if(p.captionsEdited) AlertDialog.Builder(this).setTitle(R.string.text_regenerate_title).setMessage(R.string.text_regenerate_body)
                        .setNegativeButton("Сохранить мои правки",null).setPositiveButton("Заменить") { _,_ -> session.transcribe() }.show()
                    else session.transcribe()
                }
                action("Оформление субтитров") { editCaptionStyle(p) }
                action(getString(R.string.text_caption),R.id.textCaptionButton) {
                    val start=(previewTimeMs()*1000L).coerceIn(0,p.durationUs-1)
                    editCue(CaptionCue(UUID.randomUUID().toString(),"Новая фраза",start,minOf(p.durationUs,start+2_000_000)),false)
                }
                val pages=(p.captions.size+49)/50
                captionPage=captionPage.coerceIn(0,(pages-1).coerceAtLeast(0))
                p.captions.sortedBy { it.startUs }.drop(captionPage*50).take(50).forEach { c ->
                    action("▶ ${seconds(c.startUs)}–${seconds(c.endUs)} · ${c.text}") {
                        video.pause(); video.seekTo((c.startUs/1000).toInt()+previewOffsetMs); overlay.timeUs=c.startUs; overlay.invalidate(); editCue(c,true)
                    }
                }
                if(pages>1) {
                    content.addView(label("Фразы · страница ${captionPage+1} из $pages"))
                    if(captionPage>0) action("Предыдущие фразы") { captionPage--; showContent(session.state.value) }
                    if(captionPage+1<pages) action("Следующие фразы") { captionPage++; showContent(session.state.value) }
                }
            }
        }
    }
    private fun seconds(us:Long)=String.format(Locale.ROOT,"%.3f",us/1_000_000.0)
    private fun field(parent:LinearLayout,title:String,value:String,id:Int,numeric:Boolean=false):EditText {
        parent.addView(label(title))
        return EditText(this).apply {
            this.id=id; setText(value); setTextColor(getColor(R.color.text_primary))
            inputType=if(numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            if(!numeric) filters=arrayOf(android.text.InputFilter.LengthFilter(2000))
            parent.addView(this)
        }
    }
    private class StyleFields(val position:Spinner,val font:Spinner,val size:SeekBar,val color:Spinner,val plate:CheckBox,val animation:Spinner) {
        fun value()=TextStyle(TextPosition.entries[position.selectedItemPosition],TextFont.entries[font.selectedItemPosition],
            (25+size.progress)/1000f,listOf(0xffffffff.toInt(),0xffffcc33.toInt(),0xffff8633.toInt())[color.selectedItemPosition],plate.isChecked,
            TextAnimation.entries[animation.selectedItemPosition])
    }
    private fun styleFields(parent:LinearLayout,s:TextStyle):StyleFields {
        fun spinner(title:String,options:List<String>,selected:Int):Spinner {
            parent.addView(label(title))
            return Spinner(this).apply { adapter=ArrayAdapter(this@TextEditActivity,android.R.layout.simple_spinner_dropdown_item,options); setSelection(selected); parent.addView(this) }
        }
        val position=spinner("Положение",listOf("Сверху","По центру","Снизу"),s.position.ordinal)
        position.id=R.id.textPositionField
        val font=spinner("Шрифт",listOf("Без засечек","Жирный","С засечками"),s.font.ordinal)
        font.id=R.id.textFontField
        parent.addView(label("Размер текста"))
        val size=SeekBar(this).apply { max=95; progress=((s.sizeRatio-.025f)*1000).toInt().coerceIn(0,95); parent.addView(this) }
        val colors=listOf(0xffffffff.toInt(),0xffffcc33.toInt(),0xffff8633.toInt())
        val color=spinner("Цвет",listOf("Белый","Жёлтый","Оранжевый"),colors.indexOf(s.color).coerceAtLeast(0))
        color.id=R.id.textColorField
        val plate=CheckBox(this).apply { text="Тёмная плашка"; isChecked=s.darkPlate; setTextColor(getColor(R.color.text_primary)); parent.addView(this) }
        val animation=spinner("Появление",listOf("Без анимации","Плавное","Сдвиг"),s.animation.ordinal)
        animation.id=R.id.textAnimationField
        return StyleFields(position,font,size,color,plate,animation)
    }
    private fun dialogForm():Pair<ScrollView,LinearLayout> {
        val parent=column().apply { setPadding(dp(18),0,dp(18),dp(16)) }
        return ScrollView(this).apply { addView(parent) } to parent
    }
    private fun editLayer(layer:TextLayer,existing:Boolean) {
        val p=session.state.value?.project ?: return
        val (scroll,parent)=dialogForm()
        val text=field(parent,"Текст",layer.text,R.id.textContentField)
        val start=field(parent,"Начало, секунды",seconds(layer.startUs),R.id.textStartField,true)
        val end=field(parent,"Конец, секунды",seconds(layer.endUs),R.id.textEndField,true)
        val style=styleFields(parent,layer.style)
        val dialog=AlertDialog.Builder(this).setTitle("Текст в кадре").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null)
            .apply { if(existing) setNeutralButton("Удалить") { _,_ -> session.update(p.copy(layers=p.layers.filterNot { it.id==layer.id })) } }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val times=times(start,end,p.durationUs) ?: return@setOnClickListener
            if(text.text.isBlank()) { text.error="Введите текст"; return@setOnClickListener }
            val updated=layer.copy(text=text.text.toString(),startUs=times.first,endUs=times.second,style=style.value())
            session.update(p.copy(layers=p.layers.filterNot { it.id==layer.id }+updated)); dialog.dismiss()
        } }; dialog.show()
    }
    private fun editCue(cue:CaptionCue,existing:Boolean) {
        val p=session.state.value?.project ?: return
        val (scroll,parent)=dialogForm()
        val text=field(parent,"Фраза",cue.text,R.id.textContentField)
        val start=field(parent,"Начало, секунды",seconds(cue.startUs),R.id.textStartField,true)
        val end=field(parent,"Конец, секунды",seconds(cue.endUs),R.id.textEndField,true)
        val dialog=AlertDialog.Builder(this).setTitle("Исправить субтитр").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null)
            .apply { if(existing) setNeutralButton("Удалить") { _,_ -> session.update(p.copy(captions=p.captions.filterNot { it.id==cue.id },captionsEdited=true)) } }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val times=times(start,end,p.durationUs) ?: return@setOnClickListener
            if(text.text.isBlank()) { text.error="Введите фразу"; return@setOnClickListener }
            val updated=cue.copy(text=text.text.toString(),startUs=times.first,endUs=times.second)
            session.update(p.copy(captions=(p.captions.filterNot { it.id==cue.id }+updated).sortedBy { it.startUs },captionsEdited=true)); dialog.dismiss()
        } }; dialog.show()
    }
    private fun editCaptionStyle(p:TextEditProject) {
        val (scroll,parent)=dialogForm(); val fields=styleFields(parent,p.captionStyle)
        AlertDialog.Builder(this).setTitle("Оформление субтитров").setView(scroll).setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить") { _,_ -> session.update(p.copy(captionStyle=fields.value())) }.show()
    }
    private fun times(start:EditText,end:EditText,duration:Long):Pair<Long,Long>? {
        val a=start.text.toString().replace(',','.').toDoubleOrNull()
        val b=end.text.toString().replace(',','.').toDoubleOrNull()
        if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<0 || b<=a || b>duration/1_000_000.0) {
            end.error="Нужно 0 ≤ начало < конец ≤ ${seconds(duration)}"; return null
        }
        val first=(a*1_000_000).toLong(); val last=(b*1_000_000).toLong()
        if(first>=last) { end.error="Интервал слишком короткий"; return null }
        return first to last
    }
    companion object {
        const val EXTRA_SOURCE="text_source"
        fun open(context:Context,file:File?=null) { context.startActivity(Intent(context,TextEditActivity::class.java).apply { file?.let { putExtra(EXTRA_SOURCE,it.canonicalPath) } }) }
    }
}
