# VoiceLock v2.1

Apni awaaz se phone lock karo. Phrase koi bhi ho — 3 baar record karo, phir wahi phrase bolne par turant lock. Sab on-device, koi internet nahi, koi model download nahi.

## v2.1 me naya

- **Streaming match (instant mode)** — bolte-bolte hi MFCC frames bante hain aur streaming DTW template se align karta hai. Silence ka intezaar nahi. Trigger target **<150 ms** phrase ke last word ke baad.
- **Far mode** — `MIC` + 2.5× software gain + lower VAD threshold. Door se bolne ke liye.
- **Event log** — last 6 events app me: MATCH / REJECT + score + lock action time. Pata chalta hai app sun raha hai ya nahi.
- **Vibration + toast** — lock hote hi confirm.
- **Fast fallback** — streaming miss ho jaye to 150 ms silence par full DTW + pitch check chalta hai.

## Kaise kaam karta hai

```
mic 16kHz → 10ms frames → VAD → [streaming MFCC + DTW] → lock (instant)
                                  ↘ utterance end → full DTW + pitch → lock (fallback)
```

- **Enrollment**: 3 recordings → MFCC templates + auto threshold.
- **Speaker check**: DTW template distance (voice timbre + phrase) + mean pitch. Doosri awaaz ka score threshold ke bahar rehta hai.
- **Lock**: Accessibility `GLOBAL_ACTION_LOCK_SCREEN` (Android 9+), fallback DeviceAdmin `lockNow()`.

## Setup

1. APK install karo (v1/v2 hai to pehle uninstall).
2. Mic permission do.
3. **Record sample 1/3 → 2/3 → 3/3** — har baar wahi phrase, jaise `Sagar mobile band karo`.
4. **Test phrase** → score dekho:
   - `MATCH ✅` = sahi
   - `no match ❌` = sensitivity thoda badhao
5. Door se bolna hai to **Far mode ON** karo (phir dobara enroll karna better hai, kyunki enrollment aur detection ka gain same hona chahiye).
6. **Accessibility ON**, **Battery exempt**, **Start listening**.

Ab kisi bhi app ke upar, screen off hone par bhi, phrase bolne par lock.

## Tuning

- **Sensitivity** (0–100, default 50): zyada = loose (jaldi match, false trigger risk), kam = strict.
- **Far mode**: 1–2 meter tak realistic. Phone ka mic hi limit hai — software gain SNR nahi bana sakta.
- **Event log**: `REJECT` aa raha hai to app sun raha hai par match nahi hua — sensitivity ya dobara enrollment.
- Kuch bhi event nahi aa raha = mic/VAD issue — Far mode try karo.

## Limits (sach)

- **1 ms possible nahi** — 10–20 ms mic buffering + VAD + Android lock IPC. Realistic best **~50–150 ms**.
- **Voice-print-lite, biometric nahi** — bahut similar awaaz bypass kar sakti hai.
- **Unlock voice se nahi** — Android secure keyguard kisi app se dismiss nahi hota.
- **Battery** — continuous listening ~5–8%/din.
- **OEM killers** — Xiaomi/Oppo/Vivo/Realme me autostart permission manually on karo.
- **Android 14+** — reboot ke baad app ek baar kholni padegi.

## Build

GitHub Actions: push → **Build APK** → artifact. Local: JDK 17 + SDK 34 + Gradle 8.7 → `gradle assembleDebug`.

## Structure

```
app/src/main/java/com/kai/voicelock/
├── MainActivity.kt       # enrollment, test, sensitivity, far mode, event log
├── VoiceLockService.kt   # capture loop, streaming + fallback, lock trigger
├── StreamingMatch.kt     # streaming MFCC + streaming DTW (instant path)
├── Segmenter.kt          # VAD, adaptive noise floor
├── Dsp.kt                # FFT, mel filterbank, MFCC, CMN
├── VoiceMatch.kt         # batch DTW + pitch + match decision
├── Enrollment.kt         # templates + threshold storage
├── AudioCapture.kt       # enrollment recording + gain
├── EventLog.kt
├── LockController.kt
├── LockAccessibilityService.kt
├── AdminReceiver.kt
├── BootReceiver.kt
└── Prefs.kt
```
