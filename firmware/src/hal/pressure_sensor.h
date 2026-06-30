#pragma once

namespace esp32esso::hal {

// Brew-line pressure in bar (gauge). Tier 2+ feature.
class PressureSensor {
public:
    virtual ~PressureSensor() = default;

    virtual float readBar() = 0;
    virtual bool ok() const = 0;
};

}  // namespace esp32esso::hal
