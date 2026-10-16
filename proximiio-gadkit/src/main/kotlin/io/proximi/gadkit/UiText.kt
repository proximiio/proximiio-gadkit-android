package io.proximi.gadkit

/**
 * Visitor-facing text without Android resources. Where iOS calls `String(localized:)`, core
 * returns a [Resource] key and its format arguments; the app maps the key to a string resource.
 */
internal sealed interface UiText {
    /** A string resource name, e.g. `guidance_minutes`, and its format arguments in order. */
    data class Resource(val key: String, val args: List<Any> = emptyList()) : UiText

    /** Text shown as is: configuration faults and SDK error descriptions, which iOS does not localize. */
    data class Verbatim(val text: String) : UiText
}
