#pragma once

namespace esp32esso::hal {

// Debounced binary input. Concrete implementations decide debounce strategy
// (polling, ISR + edge detection, mains zero-cross sampling, etc.).
class DiscreteInput {
public:
    virtual ~DiscreteInput() = default;

    virtual bool read() = 0;
};

}  // namespace esp32esso::hal
