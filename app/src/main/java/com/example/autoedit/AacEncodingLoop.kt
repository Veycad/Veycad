package com.veycad.app

/** The native boundary supplies input/output activity; the loop owns cancellation and liveness. */
internal object AacEncodingLoop {
    private const val STALL_TIMEOUT_NS = 20_000_000_000L
    enum class Output { IDLE, PROGRESS, END }

    fun run(checkCancelled: () -> Unit, feedInput: () -> Boolean, drainOutput: () -> Output,
            nanoTime: () -> Long = System::nanoTime) {
        var lastProgressNs = nanoTime()
        while (true) {
            checkCancelled()
            check(nanoTime() - lastProgressNs < STALL_TIMEOUT_NS) { "AAC encoder stalled" }
            if (feedInput()) lastProgressNs = nanoTime()
            when (drainOutput()) {
                Output.IDLE -> Unit
                Output.PROGRESS -> lastProgressNs = nanoTime()
                Output.END -> return
            }
        }
    }
}
