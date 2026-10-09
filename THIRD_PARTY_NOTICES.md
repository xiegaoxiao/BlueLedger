# Third-party notices

BlueLedger source is licensed under Apache-2.0. Dependencies are distributed under their own licenses; the project license does not replace those licenses.

| Component | Use | License |
| --- | --- | --- |
| AndroidX, Jetpack Compose, Material Components, Room | Application framework, UI, icons and persistence | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Language runtime, asynchronous operations and JSON | Apache-2.0 |
| Gradle Wrapper | Build bootstrap | Apache-2.0; original notices retained in wrapper scripts |
| Robolectric | JVM Android tests | MIT |
| JUnit 4 | Tests | EPL-1.0 |

Dependency coordinates and pinned versions are in `gradle/libs.versions.toml` and `app/build.gradle.kts`. Transitive dependencies may have additional licenses; consult the notices and license metadata distributed with the resolved libraries when redistributing binaries.

Category and navigation icons are provided by AndroidX Material Icons. The launcher and notification icons are defined by this project's vector resources. Public UI previews are generated from synthetic ledger fixtures. This repository does not distribute third-party accounting product code, logos, brand assets, UI screenshots or users' ledger exports. Project descriptions do not imply affiliation, authorization or endorsement by any third-party product.
