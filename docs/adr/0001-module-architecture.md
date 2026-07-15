# ADR 0001: Module architecture

Status: accepted. Protocol, domain, ADB, Android runtime, UI and Windows-native concerns are separate Gradle modules. This keeps core APIs reusable by CLI and tests and prevents Compose/native types leaking into domain code.

