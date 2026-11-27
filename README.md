# Readables: AI Voice Companion for Blind and Low-Vision Nigerians

An Android prototype that lets a blind or low-vision user control their phone by voice, set up with a caretaker's help.

## What works now
- Voice commands (speech recognition)
- Opens apps by name and reads the screen aloud (Android AccessibilityService + text to speech)
- Summon from any screen with a volume-key shortcut
- Spoken navigation: go home, click items by number, with spoken confirmation for sensitive actions
- Consent screen explaining what the assistant can and can't do

## Planned
- Caretaker setup and permission controls
- document reading
- English language
- Low-bandwidth and offline operation


## Safety and privacy
Scoped permissions, no stored passwords, spoken confirmation before calls, payments and sending, and no logging of screen content.

## Build
Android Studio, Kotlin. Open the project, connect an Android phone with USB debugging, press Run, then enable the accessibility service in Settings. API keys go in local.properties (never committed).

