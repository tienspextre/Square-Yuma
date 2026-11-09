package moe.rukamori.archivetune.audiodsp

data class TrackAnalysisResult(
    val bpm: Double = 0.0,
    val mixInTime: Double = 0.0,
    val mixOutTime: Double = 0.0,
    val contentEndTime: Double = 0.0,
    val firstBeat: Double = 0.0,
    val rawJson: String = "",
    val loudnessLufs: Double = 0.0,
    val dynamicRangeDb: Double = 0.0,
    /** A streamed song's ending, analysed on its own; [tailOffset] is where that window starts. */
    val tailRawJson: String = "",
    val tailOffset: Double = 0.0,
    /** The ONNX beat/vocal pass, in the format MixFeatures.parse documents; empty when it did not run. */
    val modelJson: String = "",
)
