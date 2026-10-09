package com.example.autoedit

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Debug-only overflow fixture; fake styles never enter the production catalogue. */
class StylePickerPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result=TextView(this).apply { text="Проверка модалки · 8 стилей"; setTextColor(-1) }
        setContentView(result)
        val styles=MontageStyleCatalog.all+(3..8).map {
            MontageStyleCatalog.sigma.copy(id="debug-$it",title="Тестовый стиль $it",
                description="Проверка длинного списка и доступности нижних пунктов") }
        StylePickerDialog.show(this,styles,"sigma") { result.text="Применён: ${it.title}" }
    }
}
