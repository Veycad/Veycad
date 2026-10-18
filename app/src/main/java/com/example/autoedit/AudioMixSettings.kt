package com.veycad.app

enum class AudioMixMode { MUSIC, SOURCE, SPEECH_AND_MUSIC }
data class AudioMixSettings(val mode: AudioMixMode, val sourceGain: Float = 1f, val musicGain: Float = 1f)
{
    init {
        require(sourceGain.isFinite() && sourceGain in 0f..1f)
        require(musicGain.isFinite() && musicGain >= 0f && musicGain <= if (mode == AudioMixMode.MUSIC) 2f else 1f)
    }
}
