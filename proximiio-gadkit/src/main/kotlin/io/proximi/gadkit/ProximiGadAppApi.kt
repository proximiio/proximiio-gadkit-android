package io.proximi.gadkit

/**
 * Marks the API of the Proximi GAD app (Guggenheim Abu Dhabi), as the iOS `@_spi(GADApp)`: the
 * design system, the app's own wristband pairing screen and the debug launch options. Not for host
 * apps: it is not covered by the versioning rule of the public API and can change in any version.
 * Host apps use [ProximiMap] and [ProximiMapHost].
 *
 * The Proximi GAD app opts in per file with `@file:OptIn(ProximiGadAppApi::class)`.
 */
@RequiresOptIn(
    message = "For the Proximi GAD app only, not for host apps. It can change in any version.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ProximiGadAppApi
