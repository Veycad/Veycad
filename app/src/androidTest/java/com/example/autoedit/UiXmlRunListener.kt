package com.example.autoedit

import android.os.SystemClock
import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File

/** Fresh machine-readable evidence even when AGP device filtering is unavailable. */
class UiXmlRunListener : RunListener() {
    private data class Case(val description: Description, val start: Long,
        var seconds: Double = 0.0, val failures: MutableList<Failure> = mutableListOf(),
        var skipped: Boolean = false)
    private val cases = linkedMapOf<Description, Case>()
    override fun testStarted(description: Description) {
        cases[description] = Case(description, SystemClock.elapsedRealtime())
    }
    override fun testFailure(failure: Failure) {
        cases.getOrPut(failure.description) { Case(failure.description, SystemClock.elapsedRealtime()) }
            .failures.add(failure)
    }
    override fun testAssumptionFailure(failure: Failure) { ignored(failure.description) }
    override fun testIgnored(description: Description) { ignored(description) }
    private fun ignored(description: Description) {
        cases.getOrPut(description) { Case(description, SystemClock.elapsedRealtime()) }.skipped = true
    }
    override fun testFinished(description: Description) {
        cases[description]?.let { it.seconds = (SystemClock.elapsedRealtime() - it.start) / 1000.0 }
    }
    override fun testRunFinished(result: Result) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        val target = File(context.filesDir, "ui-test-results.xml")
        val temporary = File(target.path + ".partial")
        temporary.outputStream().use { stream ->
            val xml = Xml.newSerializer().apply { setOutput(stream, "UTF-8") }
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "testsuite")
            xml.attribute(null, "name", "AutoEdit UI")
            xml.attribute(null, "tests", cases.size.toString())
            xml.attribute(null, "failures", cases.values.count { it.failures.isNotEmpty() }.toString())
            xml.attribute(null, "errors", "0")
            xml.attribute(null, "skipped", cases.values.count { it.skipped }.toString())
            xml.attribute(null, "time", (result.runTime / 1000.0).toString())
            xml.attribute(null, "executed", result.runCount.toString())
            for (case in cases.values) {
                xml.startTag(null, "testcase")
                xml.attribute(null, "classname", case.description.className.orEmpty())
                xml.attribute(null, "name", case.description.methodName ?: case.description.displayName)
                xml.attribute(null, "time", case.seconds.toString())
                if (case.skipped) { xml.startTag(null, "skipped"); xml.endTag(null, "skipped") }
                for (failure in case.failures) {
                    xml.startTag(null, "failure")
                    xml.attribute(null, "message", failure.message.orEmpty())
                    xml.text(failure.trace)
                    xml.endTag(null, "failure")
                }
                xml.endTag(null, "testcase")
            }
            xml.endTag(null, "testsuite")
            xml.endDocument()
            xml.flush()
        }
        check(temporary.renameTo(target))
    }
}
