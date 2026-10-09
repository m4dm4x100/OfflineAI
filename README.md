# OfflineAI — Offline AI Companion

A privacy-first Android personal AI companion designed to work without a cloud account or remote inference.

## Features
- On-device chat powered by a locally installed Gemma `.task` model through MediaPipe LLM Inference.
- Offline image understanding utilities: image labels and text extraction (ML Kit bundled models).
- On-device translation through ML Kit translation models (download language packs once; translate offline afterward).
- Local conversation history and model files; no analytics or network chat API.
- Import model files from device storage; no model is bundled in the APK.

## Requirements
- Android Studio with JDK 17, Android SDK 35.
- Android 8.0+ (API 26+); 64-bit ARM device recommended.
- A compatible MediaPipe LLM Inference `.task` model. Gemma models are large; device RAM and model compatibility determine whether inference succeeds.

## Build
Open this repository in Android Studio, allow Gradle sync, then run the `app` configuration. Or run `./gradlew assembleDebug` where Gradle is installed. The GitHub Actions workflow builds a debug APK.

## Model setup
Use the **Model** tab to import a compatible `.task` model from device storage. Models are not included because of size and licensing. Only download models from official sources and follow their license terms. Inference runs locally. Keep the app open while loading large models.

## Offline behavior
Chat and image labeling/OCR run on-device. Translation language packs may need to be downloaded once while online; after installation, translation runs on-device. The app does not send prompts, images, or translations to a server.

## Notes
This is an initial implementation. Multimodal image-question answering requires a compatible multimodal model/runtime; the built-in image tools currently provide offline image labels and OCR, and can pass their results to Gemma chat for interpretation. No claim is made that every Gemma model or phone is supported.