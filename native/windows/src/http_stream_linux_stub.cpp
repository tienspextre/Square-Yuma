#include "audio_stream.h"

namespace square {

std::unique_ptr<AudioInputStream> createHttpInputStream(
    const std::string& /*url*/,
    const std::map<std::string, std::string>& /*headers*/,
    std::string& err) {
    err = "HTTP(S) streaming on Linux requires libcurl at CMake configure time (local files only in this build)";
    return nullptr;
}

} // namespace square
