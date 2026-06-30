#pragma once

namespace esp32esso::hal {

// On/off actuator behind an opaque driver. Used for SSRs (heater), simple
// MOSFETs (valves), and any other binary load. Slow PWM is implemented in
// the control layer by toggling this on a fixed window, not here.
class DiscreteOutput {
public:
    virtual ~DiscreteOutput() = default;

    virtual void set(bool on) = 0;
    virtual bool isOn() const = 0;
};

}  // namespace esp32esso::hal
