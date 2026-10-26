# SlothAssist 🦥

A floating Android assistant that does the tapping for you. Open any app, tap the sloth, and type a command like *"search for rice 1 kg and add 2 of the first option to the cart"*. SlothAssist reads the screen through Android's accessibility tree and taps, types and scrolls for you.

Planning runs on **Gemma 4 E2B on the phone itself** (LiteRT-LM, GPU), or on Ollama on your computer. Screen contents never go to a cloud AI.

Built for the DEV [Hacktoberfest Open-Source AI Challenge: Week 3 — Slow and Steady](https://dev.to/challenges/hacktoberfest-week3-2026-10-19).

## Status

**Prototype. Not ready for everyday use.**

| | |
|---|---|
| ✅ Works | Floating bubble and panel over any app, permission onboarding, **search in any app** (~2.5 s, verified), on-device Gemma (GPU, ~3–5 s load), splitting a command into a checklist |
| ⚠️ Unreliable | Multi-step shopping end to end (e.g. Zepto: search → add → quantity). Individual steps work; full runs often stall |
| ❌ Not yet | Voice input, vision fallback for apps with no accessibility tree |

The latest build (self-verifying shopping skills, wait-for-render, top-layer-only screen reading) compiles but **has not been re-tested end to end on a device**.

## Known issues

- **Gemma 4 E2B is a weak judge of "done".** It splits commands well, but it repeats finished steps. The harness now verifies with built-in skills where it can; anything else falls back to the model.
- **Slow planning calls:** 7–20 s per Gemma call on a Pixel 9a, mostly spent reading the prompt. A full multi-step run is currently slower than doing it by hand.
- **Knowing when a screen has settled.** Progressively rendered lists (React Native) can be read too early.
- **Bottom sheets and pop-ups drawn inside the app's own window** aren't always detected, so the model may see content behind them.
- **`ACTION_CLICK` is often ignored by web content** (Chrome). There's a touch-gesture fallback.
- **Test runs modify real carts.** It never checks out or pays, but it can add items.

Suggestions from Android developers are very welcome. See the questions at the end of [POST.md](POST.md).

## Getting started

**You need:** Node 20+, an Android SDK (platform 37 is fetched by Gradle), JDK 17, and an Android phone with USB debugging. It was tested on a Pixel 9a running Android 17.

```bash
cd app
npm install
cd android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Install with `adb` rather than copying the APK over. Since Android 13, sideloaded apps get their accessibility switch locked as a "Restricted setting". If that happens, go to App info → ⋮ → Allow restricted settings.

### Choose a model

**On the phone (default).** Download [`gemma-4-E2B-it.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and push it into the app's storage (open the app once first so the folder exists):

```bash
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.slothassist/files/models/
```

**On your computer.** Run Ollama with a model (e.g. `ollama pull qwen3.5:4b`), then forward its port over USB:

```bash
adb reverse tcp:11434 tcp:11434
```

Then pick **My computer** in the app.

### Use it

1. Open SlothAssist and grant **Display over other apps** and the **SlothAssist screen helper** accessibility service.
2. Under *Local AI model*, tap **Load & test Gemma** (or **Test connection**).
3. Tap **Start floating sloth**, open any app, tap the 🦥 and type a command. Tap the sloth while it's working to stop it.

To watch what it does:

```bash
adb logcat -s SlothAgent SlothSkill SlothGemma SlothTask
```

## How it works

```
command ──▶ Gemma: split into a checklist (once)
              │
              ▼  for each sub-task
     built-in skill?  ── yes ─▶ search / add_to_cart / set_quantity
              │                  (deterministic, verifies itself from the screen)
              no
              ▼
     Gemma checkpoint: judge progress + plan the next predictable chunk of steps
              ▼
     execute the chunk by element label on the live screen,
     log what Android reported (toasts, pop-ups, counters) + a screen diff
```

| Path (`app/android/app/src/main/java/com/slothassist/`) | |
|---|---|
| `overlay/FloatingOverlayService.kt` | Bubble, command panel, status pill |
| `automation/AssistantAccessibilityService.kt` | Accessibility service; feeds the event journal |
| `automation/ScreenSnapshot.kt` | Accessibility tree → numbered, card-aware screen list |
| `automation/AgentLoop.kt`, `TaskState.kt` | The harness: checklist, chunks, task memory |
| `automation/Skills.kt` | Search, add-to-cart, set-quantity skills |
| `automation/ActionExecutor.kt` | Tap / type / scroll / back / search, with verification and safety guards |
| `automation/EventJournal.kt` | Live context from accessibility events |
| `ai/GemmaOnDevice.kt`, `ai/OllamaClient.kt` | Model backends (LiteRT-LM, Ollama) |
| `app/src/` | React Native setup screens |

## Safety

SlothAssist never taps pay, checkout or place-order buttons. It stops if you switch apps, and it has step and time limits. If it needs a login, an OTP or a clarification, it asks you instead of guessing.
