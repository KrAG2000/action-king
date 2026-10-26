---
title: "SlothAssist: an on-device Gemma agent that taps through my phone — honest status, and a call for Android help"
description: "A floating Android assistant planned by Gemma 4 running on the phone. Search works; multi-step shopping doesn't reliably yet. Here's exactly where it stands and what I'd love Android developers' advice on."
tags: devchallenge, hf26challenge, android, ai
published: false
---

*This is a submission for the [Hacktoberfest Open-Source AI Challenge: Week 3 — Slow and Steady](https://dev.to/challenges/hacktoberfest-week3-2026-10-19).*

## What I Built

This week I was a sloth on purpose. I skipped the assignments and the grocery run, and tried to build something that does the tapping for me.

**SlothAssist** is a floating 🦥 that sits on top of whatever Android app you have open. You tap it and type a command like:

> search for rice 1 kg and add 2 quantities of 1st option to the cart

The idea is that it reads the screen, works out the steps, and taps, types and scrolls through Zepto, Blinkit, Chrome or any other app for you. There's no per-app integration and no API: it sees the same screen you do, through Android's accessibility tree.

The planner is **Gemma 4 E2B running on the phone itself** (Google's LiteRT-LM runtime, on the GPU). Your screen, your cart and your commands never leave the device.

I'm not an Android developer. I come from the web and backend side, and this is my first time with accessibility services, overlays and on-device models. So this post is a progress report with honest numbers, plus a list of specific questions at the end for people who know Android better than I do.

## Honest status

| | Status | Evidence |
|---|---|---|
| Floating bubble + command panel over any app | ✅ Works | Tested over Chrome and Zepto on a Pixel 9a (Android 17) |
| Permission onboarding (overlay, accessibility, notifications) | ✅ Works | Live status for each, with a deep link to the right settings page |
| **Search in any app** | ✅ Works | Finds the search bar (or the fake one that opens it), types, submits, verifies results. About 2.5 s, no model call |
| Gemma 4 E2B on-device | ✅ Works | Loads on the GPU in about 3–5 s after the first run; every reply valid with constrained decoding |
| Splitting a command into a checklist | ✅ Works | Gemma produced `["search for rice 1 kg", "add the 1st option to the cart", "increase its quantity to 2"]` on every run |
| Tapping, reading what happened | ✅ Works | e.g. after "Add to Cart" the log shows `new: "decrease quantity", "increase quantity", "Cart, 28"` |
| **Multi-step shopping end to end** | ⚠️ Not reliable | Individual steps work; full runs in Zepto failed or stalled, and early versions added the same rice several times |
| Gemma judging when a step is *done* | ❌ Weak | The biggest problem: it kept re-doing finished steps, so I moved "done" checks out of the model |
| Voice input | ❌ Not built yet | Planned: Android's on-device speech recognizer |

**I can't hand this to a non-technical user today.** The latest build, which waits for lists to render, reads the counter on product cards and only lists the top pop-up, compiles and is pushed, but I ran out of time to re-test it end to end on the phone before the deadline.

## Demo

**Before publishing: add a screen recording or screenshots here.**

Measured on a Pixel 9a:

| Step | Time |
|---|---|
| Gemma 4 E2B load (GPU, after the first run builds its cache) | ~3–5 s (first ever load: 38 s) |
| Splitting a command into a checklist (one Gemma call) | ~8–9 s |
| Search skill: find bar, type, submit, verify (no model) | ~2.5 s |
| A planned chunk of taps, executed back to back | ~1.3 s per tap |
| Each Gemma planning call during a task | ~7–20 s, mostly prompt prefill |

### The lazy payoff, honestly

Right now, **doing it by hand is faster**. A full "search, add, set quantity" run costs several Gemma calls at 7–20 s each, which is longer than doing it myself with my thumb. Only the search skill is quicker than me. The time saving is the goal, not the current state, and closing that gap is mostly about the questions below.

## Code

[SlothAssist on GitHub](https://github.com/KrAG2000/action-king)

React Native (TypeScript) for the setup screens. Kotlin for everything that has to outlive the JS runtime: the overlay service, the accessibility service, the agent harness and the on-device model.

## How I Built It

### 1. Reading the screen without screenshots

An `AccessibilityService` can read the window of the app you're using as a tree of nodes: text, buttons, inputs, bounds, what's clickable. I flatten it into a short numbered list in reading order. Text inside a product card is folded into the card, and each button points back at its card:

```plaintext
[4] item "₹189, India Gate Basmati Rice Classic, 1 kg"
[5] button "Add to Cart" in [4]
```

### 2. One model call per tap was the wrong shape

My first version asked "what's the next action?" after every tap. With a 2B model on a phone that was slow (10–30 s per step), and the model was bad at **noticing it was finished**. It opened the right Google result, then kept tapping links on the new page. In Zepto it never marked "add to cart" as done, so it kept adding rice. My cart has opinions about this.

So I rebuilt it as a small **harness**:

- **A checklist, made once.** Gemma splits the command up front. This is the part it does best.
- **Chunks, not steps.** At each checkpoint, one call judges progress and returns the *predictable* next run of actions, up to the next page load. Steps name targets by **label** ("ADD", "+"), resolved against the live screen, so `tap ADD → tap +` runs without another model call.
- **Skills for known shapes.** "Search for X", "add the Nth option", "set quantity to N" run deterministically with no model, and **verify themselves from the screen**: the quantity counter has to actually read 2. The model is the fallback, not the judge.

### 3. Small models need small, strict output

In plain JSON mode Gemma wrote a long "thought", then padded with hundreds of tab characters (valid JSON whitespace!) until it hit the token limit. LiteRT-LM supports regex-constrained decoding, so the on-device path uses a compact one-line format:

```kotlin
private val STEP_REGEX =
  """\{"do":"(tap|type|search|scroll|back|open_url)","target":"[^"\\\n]{0,60}","nth":[1-9],"text":"[^"\\\n]{0,80}"\}"""
```

Replies went from "unusable" to "valid every time". Being valid didn't make them smart, though.

### 4. Live context from accessibility events

The most frustrating bug was *"it can't find elements that are clearly on the screen."* The real causes were mundane:

- Zepto's results list renders progressively, so my code read the screen **28 ms** after the search, before the products existed.
- A pack-size bottom sheet opened, and the model kept tapping things *behind* it.
- The model copied my own formatting (`(hint: )`) back as a target.
- I was using element *numbers* from an old screen after the screen had changed.

Instead of polling harder, I started listening. Android **pushes** accessibility events: toasts, announcements, panes appearing and disappearing, and text changes. An `EventJournal` keeps the ones that explain what an action caused, and each log entry carries them plus a before/after diff of the tree:

```plaintext
✓ tap "Add to Cart": the screen changed | new: button "decrease quantity",
  button "increase quantity", item "Cart, 28"
```

That one line says the tap worked, a stepper appeared, and the cart went from 27 to 28, all without a screenshot.

## Why Does Open Innovation Matter?

This assistant reads *everything on your screen*: your cart, your messages, whatever app you're in. That's exactly the data I don't want streamed to a hosted API to decide where to tap next.

- **It runs on the phone.** Gemma 4 E2B under the open LiteRT-LM runtime plans on the Pixel's GPU. No server, no account, works offline.
- **One model file, reused.** I'd already downloaded this Gemma file for another project, so I copied it into this app's storage and checked its SHA-256 against the published checksum.
- **Swappable.** The same harness talks to Ollama on my laptop (Qwen 3.5) when I want a bigger, faster model. It's one setting.
- **Open runtime, open knobs.** Constrained decoding was the single biggest reliability fix, and I could only reach for it because the runtime is open.

Others are working on the same problem: [Open-AutoGLM](https://dev.to/wonderlab/open-source-project-of-the-day-part-29-open-autoglm-a-phone-agent-framework-for-controlling-4p8k) is an open phone-agent framework, [this post on local-first Android automation](https://dev.to/labs_aivane_98191a4f3eab1/why-im-building-local-first-android-automation-for-ai-agents-3kgb) argues for keeping these agents on-device, and [this audit of 15 apps' accessibility](https://dev.to/okeke_chukwudubem_5f3bf49/day-12-i-took-a-week-off-from-coding-to-audit-15-android-apps-for-accessibility-whatsapp-and-3kom) matches what I found: the agent is only as good as the app's accessibility tree.

## Android developers: I'd love your advice

These are the places where I suspect I'm working against the platform instead of with it. Any pointers, even "you're using the wrong API", would help a lot.

1. **When has a screen finished changing?** After each action I re-read the whole tree every 250 ms until two reads match (up to 3 s). React Native lists render progressively, so "two equal reads" sometimes happens before the products arrive. Is there a better idle signal: debouncing `TYPE_WINDOW_CONTENT_CHANGED`, the `notificationTimeout` setting, or something else?

2. **Cheaper tree reads.** I walk the tree with `getChild()`, which as I understand it is an IPC call per node, and I do it many times per step. Are the prefetch strategies on newer Android (`FLAG_PREFETCH_DESCENDANTS_*`) or a cached-tree pattern the right way to make this fast?

3. **Clicks that don't register.** `ACTION_CLICK` works in native apps but often does nothing on web content in Chrome. I fall back to `dispatchGesture` at the element's center. Is there a more reliable rule for when to use which?

4. **What's on top?** Bottom sheets and pop-ups drawn inside the app's own window (React Native, Compose) don't always set `paneTitle` or a recognisable class name, and the content behind them still reports `isVisibleToUser = true`. How do you reliably tell what's actually visible and on top?

5. **Stable identity across re-renders.** Zepto's ids look like `product-card-add-button<uuid>`, which is great, but many apps (especially Compose) have no ids at all. What's a robust way to say "the same + button on the same product card" after the list re-renders?

6. **Typing and submitting.** I use `ACTION_SET_TEXT` and then `ACTION_IME_ENTER`, falling back to a nearby "Search" button. Some apps only react to real key events or a debounced text watcher. What's the most reliable way to type into and submit an arbitrary app's search box?

7. **Overlay and focus.** My floating panel has to take focus for typing, then give it back so the target app can receive the search and the Enter key. I toggle `FLAG_NOT_FOCUSABLE` and hide the keyboard. Is there a cleaner pattern, and what should I watch out for on Android 14+ and with Play's accessibility-service policy?

8. **On-device model speed.** Most of Gemma's 7–20 s per call is reading the prompt (system instructions plus about 50 screen elements). Does LiteRT-LM let me reuse the KV cache for a fixed system prompt across calls, or is there a better way to keep a 2B model's prompt small?

If you've built anything with `AccessibilityService`, Android automation or LiteRT-LM, I'd really appreciate a comment.

## What's next

- Re-test the latest build (self-verifying skills, wait-for-render, top-layer only) end to end on Zepto and Blinkit.
- Fold in whatever advice comes out of the questions above.
- Voice input with Android's on-device speech recognizer.

## Prize Categories

- **Best Use of Gemma**: Gemma 4 E2B runs on-device via LiteRT-LM as the planner (command → checklist → action chunks), with regex-constrained decoding.
