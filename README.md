# VoiceLock

Offline voice se phone lock karne wali Android app. Custom phrase bolo, screen lock ho jaye. Internet ki zaroorat sirf ek baar — voice model download karne ke liye. Uske baad sab on-device.

## Kya karta hai

- **Offline speech recognition** — Vosk (Kaldi) on-device model, koi cloud call nahi.
- **Custom lock phrase** — comma se alag karke multiple variants: `lock phone, band karo, so jao`.
- **Fuzzy matching** — thoda different pronunciation bhi pakad leta hai (Levenshtein similarity).
- **Lock methods**
  - `AccessibilityService` → `GLOBAL_ACTION_LOCK_SCREEN` (Android 9+, recommended, no admin)
  - `DevicePolicyManager.lockNow()` (device admin fallback)
- **Foreground service** — notification ke saath continuously sunta hai.
- **Boot pe restart** — agar tumne listening on chhoda tha.
- **Wake lock** — screen off hone par bhi CPU jagta rehta hai.

## Kya nahi karta

**Voice se unlock nahi.** Android kisi bhi third-party app ko secure keyguard (PIN/pattern/password/fingerprint) programmatically dismiss karne nahi deta. Ye OS-level security hai, bypass nahi hoti. Unlock phone ke apne credential se hi hoga. Voice sirf lock karti hai.

## Build

### GitHub Actions (sabse aasan, phone se bhi)

1. Repo ko GitHub par push karo.
2. Actions → **Build APK** → Run workflow.
3. Artifacts me `VoiceLock-debug-apk` download karo.

### Local

```bash
cd voice-lock
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK (compileSdk 34), Gradle 8.7+.

> Termux me seedha build mushkil hai (Android SDK + JDK chahiye). GitHub Actions use karna sabse clean rasta hai.

## Install aur setup

1. APK install karo (unknown sources allow).
2. App kholo → language chuno (English ya Hindi) → **Download voice model** (~40 MB, ek baar).
3. Lock phrase likho, jaise `lock phone`.
4. **Accessibility service on karo** — Settings → Accessibility → VoiceLock → On.
   - (Optional fallback: **Device admin on karo**)
5. **Battery optimization se exempt karo** — warna Android background me service maar deta hai.
6. **Start listening** dabao.

Ab jab bhi `lock phone` bologe, screen lock ho jayegi.

## Hindi model note

Hindi model output Devanagari me deta hai (`लॉक`, `बंद करो`). Agar Hindi model use kar rahe ho to phrase bhi Devanagari me likho. English model Hinglish words ko phonetic style me likhta hai, isliye `band karo` jaise phrases ke liye English model + variants behtar kaam karte hain.

## Known limits

- **Battery**: continuous listening ~5–10% per day. Ye design ka part hai.
- **Mic indicator**: Android 12+ par green dot dikhega — normal hai, foreground service mic use kar rahi hai.
- **OEM killers**: Xiaomi/Oppo/Vivo/Realme aggressive battery savers service ko maarte hain. Autostart permission bhi on karo.
- **Screen off**: kuch OEMs screen off hone par mic throttle karte hain; wake lock + battery exemption isi liye zaroori hai.
- **Boot autostart**: Android 14+ mic-type foreground service ko background se start hone nahi deta. Reboot ke baad app ek baar kholna pad sakta hai.
- **False triggers**: phrase chhota aur common ho to galti se lock ho sakta hai. 2–3 word phrase better hai.
- **Model size**: small model accuracy medium hai. Bade models (`vosk-model-en-us-0.22`, ~1.8 GB) zyada accurate hain par phone par bhaari.
- **Play Store**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` aur accessibility usage ki policy strict hai. Personal APK ke liye koi dikkat nahi.

## Structure

```
app/src/main/java/com/kai/voicelock/
├── MainActivity.kt           # UI + permissions
├── VoiceLockService.kt       # foreground service + AudioRecord + Vosk loop
├── ModelDownloader.kt        # model download + safe unzip
├── PhraseMatcher.kt          # fuzzy phrase matching
├── LockController.kt         # accessibility + device admin lock
├── LockAccessibilityService.kt
├── AdminReceiver.kt
├── BootReceiver.kt
└── Prefs.kt
```
