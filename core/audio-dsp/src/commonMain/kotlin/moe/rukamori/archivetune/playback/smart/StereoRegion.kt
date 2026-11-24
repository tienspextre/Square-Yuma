package moe.rukamori.archivetune.playback.smart

/** Planar stereo at the container's own rate; [start] is where the decode really began, on the track clock. */
class StereoRegion(val left: FloatArray, val right: FloatArray, val sampleRate: Double, val start: Double)
