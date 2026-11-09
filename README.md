# VoiceLock v2

Apni awaaz se phone lock karo. Phrase koi bhi ho — pehle 3 baar record karo, phir wahi phrase bolne par screen lock ho jayegi. Sab on-device, koi internet nahi, koi model download nahi.

## Kya badla v1 se

| v1 | v2 |
|---|---|
| Fixed phrases (`lock phone`) | **Koi bhi phrase** — tumhari recording se enroll hota hai |
| Vosk ASR (40 MB model, 1–2s latency) | **MFCC + DTW** template matching, ~0.3–0.5s latency |
| Text matching, koi speaker check nahi | **Sirf tumhari awaaz** — DTW + pitch check |
| Model download chahiye | Kuch download nahi, poora offline |
| Background me bhaari | Halka DSP loop, foreground service + wake lock |

## Kaise kaam karta hai

1. **Enrollment** — tum apna phrase 3 baar bolte ho. Har recording ka MFCC (voice fingerprint) nikal ke save hota hai. App ko phrase ka matlab samajhna hi nahi padta — "Sagar mobile band karo" bhi utna hi valid hai jitna "lock".
2. **Auto threshold** — teen samples ke aapas ke DTW distance se threshold khud calibrate hota hai.
3. **Detection** — service 10 ms frames padhti hai, VAD se bolna shuru hone par segment banata hai, 200 ms silence par phrase khatam maanta hai, phir MFCC + DTW compare. Match = lock.
4. **Sirf tumhari awaaz** — DTW template distance + mean pitch check. Doosra banda wahi phrase bole to score threshold se bahar rehta hai.
5. **Lock** — AccessibilityService `GLOBAL_ACTION_LOCK_SCREEN` (Android 9+), fallback DeviceAdmin `lockNow()`.

## Setup

1. APK install karo.
2. App kholo → mic permission do.
3. **Record sample 1/3** → apna phrase bolo (jaise "Sagar mobile band karo").
4. Wahi phrase 2 aur baar record karo — **same phrase, same tone**.
5. **Test phrase** dabao — score aur MATCH/no-match dikhega. Match na aaye to sensitivity badhao.
6. **Accessibility ON** karo (recommended).
7. **Battery optimization exempt** karo — background me zinda rehne ke liye zaroori.
8. **Start listening**.

Ab koi bhi app khula ho — YouTube, browser, game — screen off ho ya on — phrase bolne par lock ho jayega.

## Sensitivity

Slider 0–100, default 50 (= 1.0× auto threshold).

- **Kam (strict)** — galti se lock nahi hoga, par kabhi kabhi tumhara phrase miss ho sakta hai.
- **Zyada (loose)** — pakad achhi, par similar awaaz/phrase false trigger kar sakta hai.

Test button se score dekho: `score` limit se kam hona chahiye.

## Limits (sach)

- **Voice-print-lite hai, biometric nahi.** Bahut similar awaaz ya voice clone se bypass ho sakta hai. Casual "sirf mera phone" level ke liye kaafi.
- **Unlock voice se nahi** — Android secure keyguard kisi app se programmatically dismiss nahi hota. Voice sirf lock karti hai.
- **Battery** — continuous listening ~5–8%/din. Mic indicator (green dot) dikhega.
- **OEM killers** — Xiaomi/Oppo/Vivo/Realme me autostart permission manually on karni padegi, warna service mar jayegi.
- **Android 14+** — reboot ke baad mic foreground service background se start nahi hoti; app ek baar kholni padegi.
- **Phrase change** — naya phrase = dobara 3 samples record karo.

## Build

GitHub Actions: push → **Build APK** → artifact download.

Local: JDK 17 + Android SDK 34 + Gradle 8.7, phir `gradle assembleDebug`.

## Structure

```
app/src/main/java/com/kai/voicelock/
├── MainActivity.kt       # enrollment + test + permissions
├── VoiceLockService.kt   # foreground listener, mic loop, lock trigger
├── Segmenter.kt          # VAD, adaptive noise floor
├── Dsp.kt                # FFT, mel filterbank, MFCC, CMN
├── VoiceMatch.kt         # DTW + pitch + match decision
├── Enrollment.kt         # templates + threshold storage
├── AudioCapture.kt       # one-shot enrollment recording
├── LockController.kt     # accessibility / device admin lock
├── LockAccessibilityService.kt
├── AdminReceiver.kt
├── BootReceiver.kt
└── Prefs.kt
```
