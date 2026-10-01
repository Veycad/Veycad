package com.example.autoedit

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InformationalDialogsTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun privacy_dialog_explains_local_processing_and_dismisses_without_leaving_settings() {
        openSettings()
        ui.click(R.id.privacyButton)
        onView(withText("Конфиденциальность")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Видео обрабатываются только на устройстве. Veykad не отправляет исходники, " +
            "кадры или готовые монтажи в интернет. Доступ к видео используется только для выбранных вами файлов."))
            .inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button1)).check(matches(withText("Понятно"))).perform(click())
        onView(withText("Конфиденциальность")).check(doesNotExist())
        ui.awaitDisplayed(R.id.settingsContent)
        ui.click(R.id.privacyButton)
        pressBack()
        onView(withText("Конфиденциальность")).check(doesNotExist())
        ui.awaitDisplayed(R.id.settingsContent)
    }

    @Test fun help_dialog_explains_creation_results_and_disabled_action_then_returns_to_settings() {
        openSettings()
        ui.click(R.id.helpButton)
        onView(withText("Помощь")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.message)).inRoot(isDialog())
            .check(matches(withText(containsString("Добавьте видео, выберите стиль и нажмите «Создать монтаж»"))))
            .check(matches(withText(containsString("После рендера он появится в «Эдитах»"))))
            .check(matches(withText(containsString("Сохранение в галерею выполняется отдельной кнопкой или автоматически"))))
            .check(matches(withText(containsString("выберите нужное число исходников и дождитесь подбора музыки"))))
        onView(withId(android.R.id.button1)).check(matches(withText("Готово"))).perform(click())
        onView(withText("Помощь")).check(doesNotExist())
        ui.awaitDisplayed(R.id.settingsContent)
    }

    @Test fun version_dialog_uses_installed_build_identity_and_can_be_closed_by_button_and_back() {
        openSettings()
        onView(withId(R.id.versionButton)).check(matches(withText(containsString(BuildConfig.VERSION_NAME))))
        for (dismissByBack in listOf(false, true)) {
            ui.click(R.id.versionButton)
            val title = "Veykad ${BuildConfig.VERSION_NAME}"
            onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(
                "Сборка ${BuildConfig.VERSION_CODE}\nЛокальный движок автоматического монтажа")))
            if (dismissByBack) pressBack()
            else onView(withId(android.R.id.button1)).check(matches(withText("Закрыть"))).perform(click())
            onView(withText(title)).check(doesNotExist())
            ui.awaitDisplayed(R.id.settingsContent)
        }
    }

    private fun openSettings() {
        ui.launch()
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
    }
}
