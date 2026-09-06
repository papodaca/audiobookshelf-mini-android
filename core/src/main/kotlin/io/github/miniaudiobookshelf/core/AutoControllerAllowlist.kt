package io.github.miniaudiobookshelf.core

object AutoControllerAllowlist {
    fun isAllowed(packageName: String, appPackage: String): Boolean {
        if (packageName.isEmpty()) return false
        if (packageName == appPackage) return true
        if (packageName == "android") return true
        if (packageName == "android.media.session.MediaController") return true
        if (packageName == "com.android.bluetooth") return true
        if (packageName == "com.android.systemui") return true
        if (packageName == "com.google.android.gms") return true
        if (packageName == "com.google.android.googlequicksearchbox") return true
        if (packageName == "com.google.android.as") return true
        if (packageName == "com.google.android.apps.googleassistant") return true
        if (packageName == "com.google.android.carassistant") return true
        if (packageName == "com.google.android.apps.bard") return true
        if (packageName == "com.google.android.gms.car") return true
        if (packageName == "com.google.android.embedded.projection") return true
        if (packageName.startsWith("com.google.android.projection")) return true
        return false
    }

    fun isCarHost(packageName: String): Boolean {
        if (packageName == "com.google.android.gms.car") return true
        if (packageName == "com.google.android.embedded.projection") return true
        if (packageName.startsWith("com.google.android.projection")) return true
        return false
    }
}
