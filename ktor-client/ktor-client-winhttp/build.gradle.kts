import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.targets.native.tasks.*

val ideaActive: Boolean by project.extra
val serialization_version: String by project.extra

plugins {
    id("kotlinx-serialization")
}

kotlin {
    targets {
        // Workaround: 1.3.60. Possible because of the new inference.
        (this as NamedDomainObjectCollection<KotlinTarget>)

        val current = mutableListOf<KotlinTarget>()
        if (ideaActive) {
            current.add(getByName("windows"))
        } else {
            current.add(getByName("mingwX64"))
        }

        val paths = listOf("C:/Tools/msys64/mingw64/x86_64-w64-mingw32/include")
        current.filterIsInstance<KotlinNativeTarget>().forEach { platform ->
            platform.compilations.getByName("main") {
                val winhttp by cinterops.creating {
                    defFile = File(projectDir, "windows/interop/winhttp.def")
                    includeDirs(paths)
                }
            }
        }
    }

    sourceSets {
        windowsMain {
            dependencies {
                api(project(":ktor-client:ktor-client-core"))
                api(project(":ktor-http:ktor-http-cio"))
            }
        }
        windowsTest {
            dependencies {
                api(project(":ktor-client:ktor-client-features:ktor-client-logging"))
                api(project(":ktor-client:ktor-client-features:ktor-client-json"))
            }
        }

        // Hack: register the Native interop klibs as outputs of Kotlin source sets:
        if (!ideaActive) {
            val winhttpInterop by creating
            getByName("windowsMain").dependsOn(winhttpInterop)
            apply(from = "$rootDir/gradle/interop-as-source-set-klib.gradle")
            (project.ext.get("registerInteropAsSourceSetOutput") as groovy.lang.Closure<*>).invoke(
                "winhttp",
                winhttpInterop
            )
        }
    }
}
