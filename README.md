# AutoCar Controller (Android)

Kotlin + Jetpack Compose app for controlling an STM32 car through a paired BT-06 Bluetooth Classic serial module.

## Features
- Shows Android's already-paired Bluetooth devices.
- Connects with Bluetooth Classic RFCOMM / SPP.
- Sends one ASCII byte per command: `W`, `A`, `S`, `D`, `X`, `M`.
- Direction buttons send a direction on press and `X` on release.
- Stop and disconnect actions send `X`.

## Open in Android Studio
1. Extract this ZIP.
2. Open the `AutoCarController` folder in Android Studio.
3. Install Android SDK Platform 36 if prompted.
4. Use JDK 17 (Android Studio's bundled JBR 17 is suitable).
5. Allow Gradle to sync, then run the `app` configuration on an Android phone.

The project uses Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and Compose BOM 2025.10.01. If Android Studio suggests a compatible plugin/dependency update, you can accept it.

## Pair the BT-06
1. Power the BT-06 module.
2. On Android, open Settings > Bluetooth.
3. Pair with the module (often shown as `BT06`, `HC-06`, or a similar name).
4. Enter its PIN if requested. Common defaults are `1234` or `0000`; use the module's actual configured PIN.
5. Return to AutoCar Controller, tap Refresh, select the paired module, then Connect.

This app intentionally lists paired devices instead of performing a Bluetooth scan, so Android 12+ only needs the `BLUETOOTH_CONNECT` runtime permission.

## STM32 UART expectations
The BT-06 TXD pin connects to the STM32 UART RX pin, and BT-06 RXD connects to STM32 UART TX (crossed). Connect grounds together. Confirm the module's RX logic-level tolerance before connecting it to a 3.3 V MCU UART; do not assume every breakout board has the same voltage divider/regulator.

The STM32 firmware should receive bytes and compare them with uppercase ASCII characters:
- `W`: forward
- `A`: left
- `S`: backward
- `D`: right
- `X`: stop
- `M`: change mode

The app sends **no newline and no carriage return**. If your STM32 code expects line endings, change either the app protocol or firmware parser consistently.

## Important behavior / safety
- A direction button sends its command when pressed and sends `X` when released.
- The Stop button sends `X` immediately.
- Disconnect attempts to send `X` before closing the socket.
- Bluetooth cannot guarantee delivery if the link is already lost. Your STM32 firmware should implement its own failsafe: stop the motors if no valid movement command has been received within a short timeout.
- The mode button sends `M`; the app does not assume which mode is active because the current protocol has no acknowledgement from the STM32.

## Troubleshooting
- If no devices appear, pair BT-06 in Android Settings first, then tap Refresh.
- If connection fails, verify the module is powered, paired, and supports Bluetooth Classic SPP. BLE-only modules do not use this RFCOMM implementation.
- Check that the STM32 UART baud rate matches the BT-06 configuration (commonly 9600 baud, but verify yours).
- Check TX/RX crossover, common GND, and UART voltage levels.
- Test the BT-06 first with a Bluetooth serial terminal app and confirm that pressing W sends the character `W`.
