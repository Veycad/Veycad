package com.veycad.app

/** Android positional PCM channel order. A center dialogue channel must survive stereo export. */
internal object TextPcmMix {
    fun sample(inputChannels:Int,outputChannels:Int,outputChannel:Int,input:(Int)->Float):Float {
        if(outputChannels==1) return (0 until inputChannels).sumOf { input(it).toDouble() }.toFloat()/inputChannels
        if(inputChannels<=2) return input(minOf(outputChannel,inputChannels-1))
        val indices=when(inputChannels) {
            3 -> listOf(outputChannel,2)
            4 -> listOf(outputChannel,outputChannel+2)
            else -> listOf(outputChannel,2)+(4+outputChannel until inputChannels step 2).toList()
        }
        return indices.sumOf { input(it).toDouble() }.toFloat()/indices.size
    }
}
