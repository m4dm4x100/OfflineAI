# OfflineAI — Offline AI Companion

A privacy-first Android personal AI companion designed to work without a cloud account or remote inference.

## Features
- On-device chat powered by a locally installed Gemma `.task` model through MediaPipe LLM Inference.
- Offline image understanding utilities: image labels and text extraction (ML Kit bundled models).
- On-device translation through ML Kit translation models (download language packs once; translate offline afterward).
- Local conversation history and model files; no analytics or network chat API.
- Import and switch between multiple local `.task` models with a model manager, storage checks, and model status.
- Light/dark themes, animated screen transitions, and responsive button feedback; no model is bundled in the APK.
- Model-routed tool calling for private notes and personal memory, limited Android actions, read-only web search, and optional user-configured MCP servers.
- Trusted-tool allowlist in the Tools screen; MCP tools are opt-in by exact server tool name.

## Requirements
- Android Studio with JDK 17, Android SDK 35.
- Android 8.0+ (API 26+); 64-bit ARM device recommended.
- A compatible MediaPipe LLM Inference `.task` model. Gemma models are large; device RAM and model compatibility determine whether inference succeeds.

## Build
Open this repository in Android Studio, allow Gradle sync, then run the `app` configuration. Or run `./gradlew assembleDebug` where Gradle is installed. The GitHub Actions workflow builds a debug APK.

## Model setup
Open **Models** to manage installed models, switch the active model, delete unused files, or open the official LiteRT Community Gemma 3 1B model page. Review and accept the model licence on Hugging Face, download a MediaPipe-compatible `.task` variant, then import it from device storage. Import checks available space and stores models in app-private storage. Models are not bundled because of size and licensing. Inference runs locally. Keep the app open while loading large models. The current runtime does not load GGUF or `.litertlm` files directly.

## Offline behavior
Chat and image labeling/OCR run on-device. Translation language packs may need to be downloaded once while online; after installation, translation runs on-device. The app does not send prompts, images, or translations to a server. Tool use is separate: web search sends search terms to Wikipedia, and MCP calls send tool arguments to the server endpoint you configure. Local notes and personal memory are stored in app-private storage.

## Notes
This is an initial implementation. Multimodal image-question answering requires a compatible multimodal model/runtime; the built-in image tools currently provide offline image labels and OCR, and can pass their results to Gemma chat for interpretation. No claim is made that every Gemma model or phone is supported.

## Tool calling
Open **Tools** from the top navigation to enable/disable online access, select trusted local tools, and configure an optional HTTPS MCP endpoint. Chat asks the local model to return a JSON routing decision; when it selects a registered tool, the app executes the allow-listed tool and feeds the result back to the local model.

Included tools:
- **Files and notes:** list, read, and write text notes in app-private storage.
- **Personal memory:** read local memory; the model is instructed to save only when you explicitly ask it to remember something.
- **Android actions:** open Wi-Fi/Bluetooth/app settings, open HTTP(S) links, and invoke the system share sheet. No shell or arbitrary intents.
- **Web search:** read-only Wikipedia search, available only when online tools are enabled.
- **MCP:** configure a compatible HTTPS MCP endpoint and explicitly list the server tool names permitted to run automatically. Only tools reported by the server can be called.

MCP compatibility depends on the server's HTTP transport. The current lightweight client supports JSON-RPC tools/list and tools/call over HTTP(S); servers requiring additional initialization, OAuth, or transport-specific session negotiation may not work. Only configure MCP servers you trust. Tool routing depends on the installed model following the requested JSON format; unsupported models may answer normally instead of calling tools.
