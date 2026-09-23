# ADR-0001: Android-First Native Kotlin

- Status: Accepted
- Date: 2026-09-22

## Context

Audio capture, foreground services, lifecycle recovery, Wear OS integration, battery behavior, and Android platform constraints are core to the product.

## Decision

Build the first phone and watch clients natively with Kotlin and Jetpack Compose.

## Consequences

- direct access to Android platform APIs
- fewer framework layers around recording lifecycle
- easier Android/Wear OS debugging
- iOS will require a separate native client later
