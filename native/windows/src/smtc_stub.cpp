#include "smtc.h"

namespace square {

class StubSmtcController final : public SmtcController {
public:
    bool start(SmtcButtonCallback) override { return true; }
    void update(const std::string&, const std::vector<uint8_t>&) override {}
    void stop() override {}
};

std::unique_ptr<SmtcController> createSmtcController() {
    return std::make_unique<StubSmtcController>();
}

} // namespace square
