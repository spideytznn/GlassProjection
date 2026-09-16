// Local-only: dl.google.com resets Java TLS connections on this network.
// Prepend Aliyun mirrors so Gradle resolves AGP and AndroidX without Google's host.
beforeSettings {
    pluginManagement {
        repositories {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
        }
    }
    dependencyResolutionManagement {
        repositories {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
    }
}
