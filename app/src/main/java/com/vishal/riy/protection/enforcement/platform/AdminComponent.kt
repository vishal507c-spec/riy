package com.vishal.riy.protection.enforcement.platform

/**
 * The identity of RIY's Device Admin component, expressed WITHOUT an
 * `android.content.ComponentName` so that every component above this seam
 * stays pure Kotlin and unit-testable on the JVM.
 *
 * The authoritative value is `ComponentName(context, RiyDeviceAdminReceiver)`,
 * which this mirrors. [AndroidDevicePolicyBoundary] is the only place that
 * converts this back into a real `ComponentName`.
 *
 * @param packageName the package owning the admin receiver (RIY's package).
 * @param className   the fully-qualified admin receiver class name.
 */
data class AdminComponent(

    val packageName: String,

    val className: String,
) {

    /** Flattened `package/class` form, for logs and diagnostics. */
    fun flatten(): String = "$packageName/$className"
}
