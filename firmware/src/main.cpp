#include <Arduino.h>

#include "profile/machine_profile.h"

void setup() {
    Serial.begin(115200);
    delay(100);

    const auto& profile = esp32esso::profile::activeProfile();
    Serial.println();
    Serial.println(F("esp32esso starting"));
    Serial.printf("build: %s %s\n", __DATE__, __TIME__);
    Serial.printf("profile: %s %s (%s)\n",
                  profile.metadata.brand,
                  profile.metadata.model,
                  profile.metadata.voltageRegion);
    if (profile.metadata.notes && profile.metadata.notes[0] != '\0') {
        Serial.printf("notes: %s\n", profile.metadata.notes);
    }
}

void loop() {
    delay(1000);
}
