#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace square {

using SmtcButtonCallback = std::function<void(const std::string& button)>;

class SmtcController {
public:
    virtual ~SmtcController() = default;
    virtual bool start(SmtcButtonCallback onButton) = 0;
    virtual void update(const std::string& metadataJson, const std::vector<uint8_t>& artworkBytes) = 0;
    virtual void stop() = 0;
};

std::unique_ptr<SmtcController> createSmtcController();

} // namespace square
