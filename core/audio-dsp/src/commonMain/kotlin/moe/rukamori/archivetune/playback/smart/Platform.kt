package moe.rukamori.archivetune.playback.smart

/** Loads the C++ analysis (`yuma_analysis`); throws UnsatisfiedLinkError when it is not there. */
expect fun loadAnalysisLibrary()

/** Where the ONNX weights come from. */
expect object ModelSource {
    /** False until the platform can supply models (Android: after init). */
    val ready: Boolean

    /** A file ONNX Runtime can map, holding the model called [name]. */
    fun path(name: String): String
}
