# Offline HTML APK Studio (starter kit)

A starter Android WebView wrapper for an HTML app, with a native Bluetooth Classic RFCOMM bridge and cloud APK builds through GitHub Actions. No local Android Studio/Python installation is required for the cloud-build path.

## Important reality check
- This is a **starter project**, not a polished desktop drag-and-drop builder yet.
- The APK is compiled by GitHub Actions. You upload the project to a GitHub repository and run the workflow.
- Bluetooth Classic requires Android runtime permissions and nearby-device permissions on newer Android versions. Test on real Android devices.
- Android 12+ uses `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`; Android 11 and earlier use legacy Bluetooth permissions.
- Bluetooth does not work from a browser alone. This project exposes a limited native bridge to the bundled HTML.
- Only use the bridge with HTML you trust. The bridge is deliberately restricted to the local bundled page.

## Included
- Android WebView wrapper for `app/src/main/assets/index.html`
- Native Bluetooth Classic RFCOMM host/connect/send/disconnect bridge
- JavaScript callbacks for connection state and incoming messages
- GitHub Actions workflow to build a debug APK in the cloud
- No external server required for Bluetooth messaging after installation

## Build without installing tools locally
1. Create a new GitHub repository.
2. Upload the contents of this folder to the repository.
3. Open **Actions** and run **Build Android APK** (or push to `main`).
4. Download `app-debug.apk` from the workflow artifact.
5. Install the APK on both Android phones and grant Bluetooth permissions.

For first-time installation, Android may ask you to allow installing apps from your browser/file manager.

## Use your own HTML
Replace `app/src/main/assets/index.html` with your HTML file. Keep the native bridge calls listed below if you want Bluetooth functionality.

## JavaScript bridge API
The Android interface is `AndroidBluetooth`:

```js
AndroidBluetooth.isBluetoothEnabled()
AndroidBluetooth.startServer("MyOfflineChat")
AndroidBluetooth.discoverAndConnect()
AndroidBluetooth.sendMessage("Hello")
AndroidBluetooth.disconnect()
```

Native events arrive as browser events:
```js
window.addEventListener("nativeBluetoothEvent", (event) => {
  const data = event.detail;
  // data.type: "state" | "message" | "error" | "device"
  // data.state, data.message, data.name
});
```

`discoverAndConnect()` launches Android's device-enablement/discovery UI where appropriate; it does not silently enable Bluetooth. The host should tap **Host**, then the other device taps **Find & connect** and selects the host device.

## Security notes
- Pairing may require Android system confirmation.
- This is a prototype transport, not an end-to-end encrypted messenger.
- Do not use it for sensitive messages without adding authenticated encryption and a proper trust/pairing model.
- A malicious or modified HTML page could misuse a native bridge, so keep the bridge restricted to local assets.
