package com.veycad.app

/** Reject accessory hallucinations weakly attached to the observed person silhouette. */
internal object PersonMaskUnion {
    fun combine(channels: List<FloatArray>, width: Int, height: Int): FloatArray {
        val count = width * height
        require(channels.size == 6 && channels.all { it.size == count })
        val core = FloatArray(count) { i -> (channels[1][i] + channels[2][i] +
            channels[3][i] + channels[4][i]).coerceIn(0f, 1f) }
        val accessory = channels[5]
        val seen = BooleanArray(count)
        val accepted = BooleanArray(count)
        val queue = IntArray(count)
        fun neighbours(i: Int, visit: (Int) -> Unit) {
            if (i % width > 0) visit(i - 1)
            if (i % width < width - 1) visit(i + 1)
            if (i >= width) visit(i - width)
            if (i < count - width) visit(i + width)
        }
        for (seed in 0 until count) {
            if (seen[seed] || accessory[seed] < .35f) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            seen[seed] = true
            var boundary = 0
            var supported = 0
            var touchesImageBorder = false
            while (head < tail) {
                val i = queue[head++]
                // Image borders count as unsupported: a curtain reaching the image edge
                // must not appear enclosed merely because its outer boundary is offscreen.
                if (i % width == 0) { boundary++; touchesImageBorder = true }
                if (i % width == width - 1) { boundary++; touchesImageBorder = true }
                if (i < width) { boundary++; touchesImageBorder = true }
                if (i >= count - width) { boundary++; touchesImageBorder = true }
                neighbours(i) { next ->
                    if (accessory[next] >= .35f) {
                        if (!seen[next]) { seen[next] = true; queue[tail++] = next }
                    } else {
                        boundary++
                        if (core[next] >= .6f) supported++
                    }
                }
            }
            // Border-connected regions have an unobserved outer extent. A curtain
            // wrapping around hair can pass the ordinary 25% attachment test.
            // Require majority person support for these ambiguous regions; retain
            // the ordinary rule for enclosed eyewear and other interior accessories.
            val minimumSupport = if (touchesImageBorder) .5f else .25f
            if (boundary > 0 && supported.toFloat() / boundary >= minimumSupport) {
                for (j in 0 until tail) accepted[queue[j]] = true
            }
        }
        return FloatArray(count) { i ->
            var supported = accepted[i]
            neighbours(i) { if (accepted[it]) supported = true }
            (core[i] + if (supported) accessory[i] else 0f).coerceIn(0f, 1f)
        }
    }
}
