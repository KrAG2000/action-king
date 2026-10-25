# Universal AI Screen Assistant
**Functional Requirements Specification (FRS)**

**Version:** 1.0  
**Platform:** Android-first  
**Technology:** React Native, TypeScript, native Android modules (Kotlin)  
**AI architecture:** Local-first inference  
**Primary objective:** Enable users to control applications they are already using through natural-language voice or text instructions, without requiring application-specific integrations for basic UI interactions.

---

## 1. Product Overview

The application is a floating, AI-powered assistant that operates over other Android applications.

Users should be able to open an application such as Zepto, Blinkit, Amazon, Flipkart, or Myntra, activate the assistant, and issue commands such as:

- "Search for rice, 1 kg."
- "Find milk under ₹60."
- "Scroll down and find a cheaper option."
- "Go back and search for basmati rice."
- "Tell me the prices of the first three products."
- "Add this product to my cart."
- "Stop what you're doing."

The assistant interprets the request, observes the currently active interface, executes permitted UI actions, and verifies whether those actions succeeded.

The assistant must not depend on a dedicated API or custom integration for every target application to perform basic interactions such as tapping, typing, scrolling, and navigating.

### 1.1 Product principles

1. **Cross-app operation:** The assistant should work across compatible Android applications.
2. **Voice-first interaction:** Users should be able to speak commands instead of typing them.
3. **Floating access:** The assistant should remain accessible while another application is in the foreground.
4. **Local-first AI:** User commands should be processed locally whenever possible.
5. **Action verification:** The assistant must verify important actions rather than assuming they succeeded.
6. **User control:** Users must be able to pause, cancel, or stop an operation.
7. **Safe execution:** Sensitive actions must require appropriate confirmation.
8. **Graceful degradation:** When the assistant cannot understand or control an interface, it must explain the limitation rather than silently fail.

---

# 2. Functional Requirements

Priority definitions:

- **P0:** Mandatory for the initial functional MVP.
- **P1:** Important enhancement after the core workflow works.
- **P2:** Future capability.

## FR-01: Application Onboarding and Permissions

| ID | Functional requirement | Priority |
|---|---|---|
| FR-001 | The application shall provide an onboarding flow explaining the assistant's capabilities and limitations. | P0 |
| FR-002 | The application shall explain why floating-window permission is required. | P0 |
| FR-003 | The application shall direct the user to Android settings to grant overlay permission. | P0 |
| FR-004 | The application shall explain the Accessibility Service and request that the user enable it through Android settings. | P0 |
| FR-005 | The application shall detect whether the required permissions and services are enabled. | P0 |
| FR-006 | The application shall indicate which required capabilities are unavailable because of missing permissions. | P0 |
| FR-007 | The application shall allow the user to revoke or disable supported capabilities through the appropriate Android settings. | P1 |
| FR-008 | The application shall explain how voice input, screenshots, and local AI processing work before requesting relevant permissions. | P0 |

**Acceptance criteria**

- A new user can complete onboarding without manually editing configuration files.
- The app accurately reports whether overlay and accessibility capabilities are available.
- If a required permission is missing, the app explains the next step.
- The application does not imply that granting overlay permission also grants accessibility access.

## FR-02: Floating Assistant Window

| ID | Functional requirement | Priority |
|---|---|---|
| FR-009 | The application shall provide a floating assistant button that can appear above compatible foreground applications. | P0 |
| FR-010 | The user shall be able to drag the floating button to a different screen position. | P0 |
| FR-011 | The application shall provide a compact assistant panel when the floating button is tapped. | P0 |
| FR-012 | The panel shall support text input, voice input, task execution, and cancellation. | P0 |
| FR-013 | The floating panel shall be dismissible without terminating the current target application. | P0 |
| FR-014 | The user shall be able to minimize the panel back to the floating button. | P0 |
| FR-015 | The application shall remember the user's preferred floating-button position. | P1 |
| FR-016 | The application shall support compact and expanded panel modes. | P1 |
| FR-017 | The application shall provide a visible indication when the assistant is listening, processing, or executing actions. | P0 |
| FR-018 | The application shall allow the user to hide the floating button temporarily. | P1 |
| FR-019 | The application shall recover gracefully when the floating window is dismissed, interrupted, or recreated. | P0 |
| FR-020 | The application shall prevent the floating panel from unnecessarily blocking important controls in the underlying application. | P1 |

**Acceptance criteria**

- The user can open the target application and activate the assistant without switching to the assistant's main screen.
- Opening or dismissing the assistant does not intentionally navigate away from the target application.
- The floating button remains usable while the target application is in the foreground, subject to Android restrictions.
- The user can cancel an executing task from the assistant panel.

**Platform note:** A React Native component alone cannot create a system-wide Android overlay. This requirement needs native Android implementation, normally using `WindowManager` and an appropriately managed native component.

## FR-03: Text-Based Commands

| ID | Functional requirement | Priority |
|---|---|---|
| FR-021 | The assistant shall provide a text field for entering natural-language instructions. | P0 |
| FR-022 | The assistant shall accept instructions that describe a single UI action. | P0 |
| FR-023 | The assistant shall accept instructions that describe a sequence of UI actions. | P0 |
| FR-024 | The assistant shall display the recognized instruction before execution when clarification or confirmation is required. | P0 |
| FR-025 | The assistant shall allow users to edit or resubmit a command. | P0 |
| FR-026 | The assistant shall support follow-up instructions that modify an existing task. | P1 |
| FR-027 | The assistant shall distinguish a new task from a continuation of the current task. | P1 |
| FR-028 | The assistant shall display an understandable error when a command is ambiguous or unsupported. | P0 |

**Examples**

- "Search for rice, 1 kg."
- "Scroll down."
- "Open the second result."
- "Go back and search for basmati rice instead."
- "Stop the current task."

## FR-04: Voice Input and Audio Interaction

| ID | Functional requirement | Priority |
|---|---|---|
| FR-029 | The assistant shall allow users to initiate voice input from the floating panel. | P0 |
| FR-030 | The assistant shall request microphone permission before recording audio. | P0 |
| FR-031 | The assistant shall convert spoken commands into text using an available speech-recognition mechanism. | P0 |
| FR-032 | The assistant shall display the recognized transcription before execution when appropriate. | P0 |
| FR-033 | The user shall be able to correct a transcription before executing a command. | P0 |
| FR-034 | The assistant shall indicate when microphone capture is active. | P0 |
| FR-035 | The user shall be able to stop recording manually. | P0 |
| FR-036 | The assistant shall handle recognition failures, silence, and unintelligible speech. | P0 |
| FR-037 | The assistant shall support a configurable speech-input language where the selected recognition engine supports it. | P1 |
| FR-038 | The assistant shall support hands-free continuous listening as an optional future capability. | P2 |
| FR-039 | The assistant shall optionally speak short status messages or results using text-to-speech. | P1 |
| FR-040 | The assistant shall not require continuous microphone access for ordinary tap-to-speak interactions. | P0 |

**Acceptance criteria**

- The user can tap the microphone, say "Search for rice, 1 kg," and see the recognized text.
- The user can edit the text before submitting it.
- Microphone denial produces a clear explanation and leaves text input available.
- Audio is not retained longer than necessary to complete the requested processing.

**Implementation note:** React Native can provide the interface, but voice recognition requires a suitable speech-recognition library or native Android integration. On-device recognition availability depends on the selected engine, device, and installed language resources. Local AI inference does not automatically mean speech recognition is local.

## FR-05: Active Application Detection

| ID | Functional requirement | Priority |
|---|---|---|
| FR-041 | The assistant shall attempt to identify the currently active application when a task begins. | P0 |
| FR-042 | The assistant shall inspect the accessible interface of the active application when permitted. | P0 |
| FR-043 | The assistant shall identify available text fields, buttons, links, lists, and other accessible UI elements. | P0 |
| FR-044 | The assistant shall obtain accessible labels, text, bounds, and available actions where exposed by the target application. | P0 |
| FR-045 | The assistant shall detect relevant UI changes after performing an action. | P0 |
| FR-046 | The assistant shall identify when the active application changes during task execution. | P0 |
| FR-047 | The assistant shall avoid executing a pending action against an unintended application. | P0 |
| FR-048 | The assistant shall inform the user when the target application cannot be inspected sufficiently. | P0 |
| FR-049 | The assistant shall support a configurable list of applications in which automation is disabled. | P1 |

**Acceptance criteria**

- When the user opens a supported shopping app and issues a command, the assistant attempts to inspect that app's active interface.
- When the user switches to another application during execution, the assistant pauses or cancels actions that would otherwise target the wrong app.
- The assistant does not assume that every visible element is accessible through the Android accessibility tree.

## FR-06: UI Interaction and Action Execution

| ID | Functional requirement | Priority |
|---|---|---|
| FR-050 | The assistant shall be able to activate supported accessible UI elements. | P0 |
| FR-051 | The assistant shall be able to enter text into supported text fields. | P0 |
| FR-052 | The assistant shall be able to perform supported scrolling gestures. | P0 |
| FR-053 | The assistant shall be able to navigate backward using supported Android interaction mechanisms. | P0 |
| FR-054 | The assistant shall be able to submit searches through supported UI controls. | P0 |
| FR-055 | The assistant shall be able to select an item from a visible list of results. | P1 |
| FR-056 | The assistant shall be able to tap a screen coordinate when a supported accessibility action cannot be used and a reliable target location has been established. | P1 |
| FR-057 | The assistant shall verify that a target element remains valid before executing a delayed action. | P0 |
| FR-058 | The assistant shall stop or replan when the interface changes unexpectedly. | P0 |
| FR-059 | The assistant shall limit repeated actions when the expected result does not occur. | P0 |
| FR-060 | The assistant shall provide a mechanism to interrupt execution immediately. | P0 |

**Initial supported action set**

1. Tap an accessible element.
2. Enter or replace text.
3. Submit a search.
4. Scroll vertically.
5. Navigate back.
6. Select an accessible result.
7. Wait for an expected interface change.
8. Stop execution.

Additional actions, such as long-pressing, swiping horizontally, or interacting with complex controls, can be introduced later.

## FR-07: Local AI and Task Planning

| ID | Functional requirement | Priority |
|---|---|---|
| FR-061 | The assistant shall process user commands using a configured local AI model. | P0 |
| FR-062 | The assistant shall convert natural-language requests into structured action plans. | P0 |
| FR-063 | The assistant shall use a constrained action schema rather than executing arbitrary model-generated code. | P0 |
| FR-064 | The assistant shall validate generated action plans before execution. | P0 |
| FR-065 | The assistant shall support multi-step plans for simple tasks. | P0 |
| FR-066 | The assistant shall use observed UI state to determine the next action. | P0 |
| FR-067 | The assistant shall re-evaluate its plan when the observed interface differs from expectations. | P0 |
| FR-068 | The assistant shall request clarification when multiple plausible interpretations could materially change the outcome. | P0 |
| FR-069 | The assistant shall enforce maximum action counts and task time limits. | P0 |
| FR-070 | The assistant shall support cancellation of model inference and pending actions where technically possible. | P0 |
| FR-071 | The assistant shall display the current task status and explain why it stopped. | P0 |
| FR-072 | The assistant shall support configurable local inference backends. | P1 |
| FR-073 | The assistant shall optionally support local visual understanding for interfaces that cannot be interpreted adequately through accessibility metadata. | P1 |

**Example action plan**

User instruction:

"Search for rice, 1 kg."

Possible plan:

1. Inspect the current screen.
2. Identify the search field.
3. Focus the field.
4. Enter "rice 1 kg."
5. Submit the search.
6. Wait for the results.
7. Verify that a search-results interface is visible.
8. Report completion or explain the failure.

The AI proposes the plan, but the execution layer validates every action.

## FR-08: Screenshot and Visual Understanding

| ID | Functional requirement | Priority |
|---|---|---|
| FR-074 | The assistant shall use accessible UI metadata as its first choice for identifying interface elements. | P0 |
| FR-075 | The assistant shall optionally capture the active screen when visual analysis is needed and permitted. | P1 |
| FR-076 | The assistant shall identify relevant visual controls when accessibility metadata is insufficient, using a supported local vision model. | P1 |
| FR-077 | The assistant shall map visual targets to screen coordinates when coordinate-based interaction is supported. | P1 |
| FR-078 | The assistant shall verify a visual target against the current screen before acting on it. | P1 |
| FR-079 | The assistant shall avoid capturing or processing screenshots when the task can be completed without them. | P0 |
| FR-080 | The assistant shall handle secure screens or capture failures without repeatedly attempting prohibited operations. | P0 |

Visual understanding is an optional fallback, not a prerequisite for the first MVP.

## FR-09: Shopping and Product Search Workflows

| ID | Functional requirement | Priority |
|---|---|---|
| FR-081 | The assistant shall support natural-language product-search requests. | P0 |
| FR-082 | The assistant shall recognize product names, quantities, and units when expressed clearly. | P0 |
| FR-083 | The assistant shall submit a product search through the active application's supported UI. | P0 |
| FR-084 | The assistant shall detect whether the search results appear successfully. | P0 |
| FR-085 | The assistant shall support follow-up searches that modify the original query. | P1 |
| FR-086 | The assistant shall attempt to identify visible product names and prices when they are accessible. | P1 |
| FR-087 | The assistant shall support selecting a product from visible results when the relevant controls can be identified. | P1 |
| FR-088 | The assistant shall support adding a selected product to a cart when the interface exposes a usable interaction path. | P1 |
| FR-089 | The assistant shall verify whether the selected product appears to have been added successfully. | P1 |
| FR-090 | The assistant shall distinguish between adding an item to a cart and placing an order. | P0 |
| FR-091 | The assistant shall require explicit user confirmation before finalizing a purchase. | P0 |

The initial MVP should prove generic cross-app interaction through search. Shopping-specific product interpretation and cart management can follow.

## FR-10: Task Execution, Progress and Recovery

| ID | Functional requirement | Priority |
|---|---|---|
| FR-092 | The assistant shall display the current task status. | P0 |
| FR-093 | The assistant shall indicate the action currently being attempted. | P0 |
| FR-094 | The assistant shall report successful completion of verified actions. | P0 |
| FR-095 | The assistant shall report when an action cannot be verified. | P0 |
| FR-096 | The assistant shall retry eligible transient failures a limited number of times. | P0 |
| FR-097 | The assistant shall avoid blindly repeating non-idempotent actions. | P0 |
| FR-098 | The assistant shall pause execution when user intervention is necessary. | P0 |
| FR-099 | The assistant shall allow the user to resume, cancel, or restart a supported task. | P1 |
| FR-100 | The assistant shall preserve sufficient task context to support follow-up commands during an active session. | P1 |
| FR-101 | The assistant shall stop safely when the task exceeds configured limits. | P0 |

## FR-11: Privacy, Security and User Control

| ID | Functional requirement | Priority |
|---|---|---|
| FR-102 | The assistant shall explain the information it can access through its enabled permissions. | P0 |
| FR-103 | The assistant shall make local processing the default for supported AI and speech workloads. | P0 |
| FR-104 | The assistant shall not transmit screenshots, accessibility data, or audio to external services without a clearly disclosed and authorized processing path. | P0 |
| FR-105 | The assistant shall provide a visible way to stop active automation. | P0 |
| FR-106 | The assistant shall avoid collecting or retaining screen content that is not required for the current task. | P0 |
| FR-107 | The assistant shall support deletion of stored task history and preferences. | P1 |
| FR-108 | The assistant shall prevent the AI from executing arbitrary shell commands or unrestricted code. | P0 |
| FR-109 | The assistant shall pause when authentication, payment, CAPTCHA, or other sensitive verification requires user intervention. | P0 |
| FR-110 | The assistant shall not silently submit purchases or payments. | P0 |
| FR-111 | The assistant shall respect Android's restrictions on secure screens and protected content. | P0 |
| FR-112 | The assistant shall stop automation when required permissions are revoked. | P0 |

## FR-12: Settings and User Preferences

| ID | Functional requirement | Priority |
|---|---|---|
| FR-113 | The user shall be able to enable or disable the floating assistant. | P0 |
| FR-114 | The user shall be able to select the preferred interaction mode: text, voice, or both. | P1 |
| FR-115 | The user shall be able to configure task timeouts and retry limits within safe bounds. | P1 |
| FR-116 | The user shall be able to select supported local AI models. | P1 |
| FR-117 | The user shall be able to view which required capabilities are currently enabled. | P0 |
| FR-118 | The user shall be able to disable visual screen analysis. | P1 |
| FR-119 | The user shall be able to review recent task summaries. | P1 |
| FR-120 | The user shall be able to clear task history and reset preferences. | P1 |

---

# 3. Non-Functional Requirements

These requirements describe how the application should behave rather than which features it provides.

| ID | Requirement | Target |
|---|---|---|
| NFR-001 | Platform support | Android-first; establish a documented minimum supported Android version during implementation |
| NFR-002 | Application framework | React Native with TypeScript |
| NFR-003 | Native integration | Kotlin modules for overlay, accessibility, and other required Android capabilities |
| NFR-004 | Local inference | Core supported commands shall work without an external AI API |
| NFR-005 | Action verification | Every consequential UI action shall have an appropriate success or failure check |
| NFR-006 | Cancellation | The system shall stop scheduling new actions immediately after cancellation; an already-dispatched system action may not be reversible |
| NFR-007 | Resource usage | Inference and screenshot capture shall be limited to what the task requires |
| NFR-008 | Reliability | Failed or uncertain actions shall not be reported as successful |
| NFR-009 | Compatibility | Unsupported or inaccessible UI patterns shall produce a clear limitation message |
| NFR-010 | Privacy | Temporary screen and audio data shall be discarded when no longer required |
| NFR-011 | Maintainability | AI planning, native interaction, and UI presentation shall be separated into distinct modules |
| NFR-012 | Testability | Action planning and state transitions shall be testable independently of real shopping applications |
| NFR-013 | Accessibility | The assistant's own controls shall have meaningful labels and usable touch targets |
| NFR-014 | Observability | Debug logs shall record action types and outcomes without unnecessarily storing sensitive screen contents |
| NFR-015 | Safety | Destructive or consequential actions shall require appropriate confirmation |

Performance targets for voice transcription, local inference, and action execution should be established after selecting the model and test device. Local inference latency depends heavily on hardware and model size, so fixed response-time guarantees should not be assumed before benchmarking.

---

# 4. MVP Scope

The first version should demonstrate the complete interaction loop rather than attempt every feature.

## P0: Mandatory MVP features

1. Android onboarding and permission guidance.
2. Floating assistant bubble.
3. Expandable floating prompt.
4. Text-based natural-language commands.
5. Tap-to-speak voice input.
6. Local speech recognition where a suitable engine is available.
7. Local AI command interpretation.
8. Structured and validated action plans.
9. Accessibility-tree inspection.
10. Text-field identification and text entry.
11. UI tapping and scrolling.
12. Search submission.
13. Screen-state verification.
14. Action cancellation and basic recovery.
15. Visible task status and error messages.
16. Safe handling of sensitive actions.

### MVP demonstration scenario

The user opens Zepto.

They tap the floating assistant and say:

"Search for rice, 1 kg."

The assistant transcribes the command, interprets it locally, inspects the active screen, identifies the search field, enters the query, submits it, and verifies that search results appear.

The same interaction should then be demonstrated in at least one other application.

The demonstration must use real application interactions, not mock screens, if it is intended to establish cross-app compatibility.

## P1: Post-MVP enhancements

1. Visual recognition fallback.
2. Product-result interpretation.
3. Cart interactions.
4. Multi-step shopping tasks.
5. Follow-up commands and conversational context.
6. Spoken status responses.
7. User preferences and task history.
8. Configurable local inference backends.
9. More robust recovery from unexpected dialogs.
10. Application-specific compatibility improvements discovered during testing.

## P2: Future enhancements

1. Hands-free continuous listening.
2. Multi-item shopping workflows with product comparison.
3. Budget-aware product selection.
4. Cross-platform support through separately designed integrations.
5. Custom workflows and reusable task templates.
6. Advanced local vision and interaction planning.
7. Optional integrations with supported automation frameworks.

---

# 5. React Native and TypeScript Implementation Boundaries

React Native should handle the application interface and orchestration, but it cannot independently implement every required system-level capability.

| Component | Recommended implementation |
|---|---|
| Main settings and onboarding | React Native + TypeScript |
| Floating assistant UI | React Native UI hosted through a native Android overlay |
| Overlay lifecycle | Kotlin |
| Accessibility service | Kotlin |
| Gesture dispatch and accessible-node interaction | Kotlin |
| Voice interface | React Native |
| Speech recognition | Native Android integration or a compatible library |
| Local inference orchestration | TypeScript |
| Model inference runtime | Compatible native runtime or local inference engine |
| Action-plan validation | TypeScript + Zod |
| Task state machine | TypeScript |
| Secure preferences | Appropriate Android storage mechanisms |
| Task history | SQLite or another suitable local store |

### Suggested project structure

```text
src/
├── app/
│   ├── onboarding/
│   ├── settings/
│   └── history/
├── components/
│   ├── FloatingAssistant.tsx
│   ├── AssistantPanel.tsx
│   ├── VoiceInput.tsx
│   └── TaskStatus.tsx
├── ai/
│   ├── inferenceClient.ts
│   ├── commandParser.ts
│   └── actionPlanner.ts
├── automation/
│   ├── actionSchema.ts
│   ├── actionExecutor.ts
│   ├── actionVerifier.ts
│   └── taskStateMachine.ts
├── services/
│   ├── permissions.ts
│   ├── speechRecognition.ts
│   └── taskHistory.ts
└── types/
    ├── actions.ts
    ├── tasks.ts
    └── accessibility.ts

android/
└── app/src/main/java/.../
    ├── FloatingOverlayService.kt
    ├── AssistantAccessibilityService.kt
    ├── AccessibilityBridgeModule.kt
    └── SpeechRecognitionModule.kt
```

The exact native-module design should be decided after selecting the React Native version and the Android service lifecycle architecture.

The React Native JavaScript runtime should not be assumed to remain alive indefinitely when the application is backgrounded. Native Android components must manage the overlay and accessibility-service lifecycle correctly.

---

# 6. Acceptance Criteria for the First Release

The MVP is ready for demonstration when all of the following pass:

- [ ] A user can complete onboarding and enable the required permissions.
- [ ] A floating assistant appears over a supported third-party application.
- [ ] The assistant can be moved, opened, minimized, and dismissed.
- [ ] A user can enter a command through text.
- [ ] A user can issue a command through voice.
- [ ] The assistant can process a supported command using local AI.
- [ ] The assistant can inspect the accessibility tree of a supported application.
- [ ] The assistant can identify and activate a search field.
- [ ] The assistant can enter and submit a search query.
- [ ] The assistant verifies whether the search was successful.
- [ ] The assistant reports failures without falsely claiming success.
- [ ] The user can interrupt an executing task.
- [ ] The assistant pauses when it cannot safely determine the next action.
- [ ] The assistant does not autonomously finalize purchases or payments.
- [ ] The basic workflow succeeds in at least two different applications.
- [ ] The assistant handles missing permissions and unsupported interfaces gracefully.

**Release criterion:** A working demonstration of the complete voice-to-action workflow across two applications is more important than implementing every P1 feature.

---

# 7. Explicit Out-of-Scope Items for the MVP

To keep the first version achievable, the following are excluded:

- Autonomous checkout or payment.
- Bypassing CAPTCHAs or authentication.
- Circumventing protected screens or application security mechanisms.
- Guaranteed support for every Android application.
- iOS system-wide UI automation.
- Cloud-dependent AI as a mandatory requirement.
- Unrestricted background recording.
- Unrestricted execution of model-generated code.
- Guaranteed Google Play approval for general-purpose autonomous accessibility automation.

The implementation must be reviewed against applicable Android and Google Play policies before public distribution.