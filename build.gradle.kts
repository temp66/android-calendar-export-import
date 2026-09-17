// Plugin versions live in gradle/libs.versions.toml. They are declared here so the version is
// resolved once for the whole build, and applied in the module that needs them.
plugins {
    alias(libs.plugins.android.application) apply false
}
