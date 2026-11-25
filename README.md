# Field Inspections (Android demo app)

A small native Android app (Kotlin, Jetpack Compose) for the **Native Blocks** demo: an inspector picks a site, goes through a checklist and submits the inspection. The app publishes it as a JSON message to a Native Blocks **queue** over its REST API (`POST {queue}/messages`).

- Single module, `compileSdk 34`, AGP 8.5, Gradle 8.7, JDK 17. `./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- Application ID `com.acme.fieldinspections`, version 2.3.0 (41).

## Connecting it to a queue

The queue URL defaults to `http://10.0.2.2:3105` (the host machine, seen from the Android emulator). The API key is **never in this repository**: give it to the app at launch, or type it in **Queue setup** (the gear icon).

```bash
adb shell am start -S -n com.acme.fieldinspections/.MainActivity \
  --es queueUrl http://10.0.2.2:3105 --es apiKey <ncq_ key with the write scope>
```

The values are kept on the device until the app is uninstalled.

## Demo note

A mobile app can't keep a secret. In the product the app would call the queue with the signed-in user's identity token, not an API key; the key is a stand-in for the demo.

Cleartext HTTP is allowed only for `10.0.2.2` and `localhost` (`res/xml/network_security_config.xml`), for the local demo queue.
