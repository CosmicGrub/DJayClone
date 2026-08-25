package com.oblivion.djayclone

/**
 * Small, dependency-free radix-2 Cooley-Tukey FFT - public-domain algorithm,
 * pure Kotlin, no NDK/JNI, no external library. Transforms `re`/`im` in
 * place; both must share a power-of-2 length.
 *
 * Shared utility rather than something private to one feature: any future
 * real frequency-domain analysis off this app's already-decoded PCM (the
 * waveform's spectral coloring today; harmonic key detection would need the
 * same primitive later) should call this one implementation, not grow a
 * second copy.
 */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch: ${re.size} vs ${im.size}" }
        require(n and (n - 1) == 0) { "FFT size must be a power of 2, got $n" }
        if (n <= 1) return

        // Bit-reversal permutation - standard in-place iterative-FFT setup.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Iterative Cooley-Tukey butterflies, doubling the sub-transform
        // length each pass until it covers the whole array.
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angleStep = -2.0 * Math.PI / len
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val angle = angleStep * k
                    val wr = kotlin.math.cos(angle).toFloat()
                    val wi = kotlin.math.sin(angle).toFloat()
                    val evenIdx = start + k
                    val oddIdx = start + k + half
                    val tr = re[oddIdx] * wr - im[oddIdx] * wi
                    val ti = re[oddIdx] * wi + im[oddIdx] * wr
                    re[oddIdx] = re[evenIdx] - tr
                    im[oddIdx] = im[evenIdx] - ti
                    re[evenIdx] += tr
                    im[evenIdx] += ti
                }
                start += len
            }
            len = len shl 1
        }
    }
}
