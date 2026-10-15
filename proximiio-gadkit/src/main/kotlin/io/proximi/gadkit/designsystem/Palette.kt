package io.proximi.gadkit.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Colours from the Figma "Color palette" section (node 6902:47826), as in iOS `Palette.swift`.
 * The hex values are final; the semantic names are our reading of the design and are to be
 * confirmed with the design team.
 */
public object Palette {
    // Brand spectrum, in the order the palette shows it.
    public val lime: Color = Color(0xFF65FF2E)
    public val mint: Color = Color(0xFF00FFB2)
    public val cyan: Color = Color(0xFF34EEFF)
    public val blue: Color = Color(0xFF6FA6FF)
    public val lavender: Color = Color(0xFFC0B3FF)
    public val violet: Color = Color(0xFFBB87F3)
    public val purple: Color = Color(0xFFA566FF)
    public val orchid: Color = Color(0xFFCF63C0)
    public val red: Color = Color(0xFFFF5353)
    public val orange: Color = Color(0xFFFA8352)
    public val yellow: Color = Color(0xFFFFE523)

    // Neutrals
    public val white: Color = Color(0xFFFFFFFF)
    public val ink: Color = Color(0xFF2B2B2B)

    // Glass and badges (Figma components)
    public val glassEdge: Color = Color(0xFFE6E6E6)
    public val badgeStroke: Color = Color(0xFFEEEDED)

    /** Red of the active stop and the destination marker (Figma map frames). */
    public val activeRed: Color = Color(0xFFED3731)

    /** Blue of the "you are here" marker (`You_are_here.svg`). */
    public val position: Color = Color(0xFF3F69FF)

    // Inputs (Figma "Inputs" section)
    public val inputFill: Color = Color(0xFFEEFAF0)
    public val inputStroke: Color = Color(0xFFDDDDDD)
    public val errorLabel: Color = Color(0xFFFF0000)
    public val errorStroke: Color = Color(0xFFFF8686)

    /** Opacity of a disabled control (buttons and inputs). */
    public const val DISABLED_OPACITY: Float = 0.4f

    /**
     * The colour of a museum level on the map ("MAP Functionality Approach 02", Level 0–4).
     * Read from the level previews; to be confirmed with the design team.
     */
    public fun level(level: Int): Color = when (level) {
        0 -> mint
        1 -> blue
        2 -> purple
        3 -> yellow
        4 -> orange
        else -> ink
    }
}
