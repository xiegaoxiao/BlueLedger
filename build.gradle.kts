plugins {
    // AGP 9 内置 Kotlin 支持，因此不声明 org.jetbrains.kotlin.android。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
