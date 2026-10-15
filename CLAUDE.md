# ProximiioGADKit for Android: rules for agents

Nagarro integrates this library into the Guggenheim Abu Dhabi app. The README section "Changelog"
is their record of every change.

- Every change of the library adds an entry under `[Unreleased]` in the README changelog, in the
  same commit. The entry says what changed for the host app and what the host app has to do.
- Mark a change **BREAKING:** when the host app has to change its code: a public API is renamed or
  removed, a parameter is new and required, a type changes, or a default changes. When unsure,
  treat the change as breaking.
- Mark a change **Visible:** when the screens or the behaviour change and the API stays the same.
- Before the user approves a push or a tag, name the breaking changes in the answer on their own
  line: "Breaking change for Nagarro: …", or "No breaking change".
- A release moves the `[Unreleased]` entries under `### [x.y.z] — YYYY-MM-DD`. A tag `X.Y.0` has the
  behaviour of the iOS tag `X.Y.0` of `proximiio/proximiio-gadkit-ios`.
- Commits name the author only. All text (README, comments, commits) follows the Annina rules:
  `/Volumes/ExtremeSSD/proximiio/proximiio-ios-sdk-v6/docs/site/ANNINA-RULES.md`.
