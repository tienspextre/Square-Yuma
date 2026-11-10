package moe.rukamori.archivetune.playback.smart

/** The desktop decodes with ffmpeg (Automix.kt); ModelPass only needs this type of the phone's decoder. */
object AudioDecoder {
    /** Planar stereo at the container's own rate; [start] is where the decode really began, on the track clock. */
    class StereoRegion(val left: FloatArray, val right: FloatArray, val sampleRate: Double, val start: Double)
}
