val versionCatalogs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
val libs = versionCatalogs.named("libs")
val supabaseVersion = libs.findVersion("supabase").get().requiredVersion

dependencies {
    "commonMainImplementation"("io.github.jan-tennert.supabase:realtime-kt:$supabaseVersion")
}
