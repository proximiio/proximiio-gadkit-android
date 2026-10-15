#!/bin/sh
# Converts the iOS level change animations to animated WebP for Android.
#
# Android decodes animated WebP and GIF (ImageDecoder, API 28+), not APNG. The iOS resources
# `Resources/LevelChange/level-{up,down}-{en,ar}.apng` (52 frames, 40 ms each, 480 px wide, alpha
# only) become `proximiio-gadkit/src/main/res/raw/level_{up,down}_{en,ar}.webp`: lossless, alpha kept, the
# frame durations of the APNG, played once. The last frame also goes to
# `proximiio-gadkit/src/main/res/drawable-nodpi/level_{up,down}_{en,ar}_last.png`, shown when animations are off
# and on API 26–27, which cannot decode animated WebP. The first frame of the up animation goes to
# `level_up_first.png` (no text yet, the same in EN and AR), shown during a lift ride without a
# known target floor. The library tints them all with the position colour.
#
# Needs ffmpeg and img2webp (Homebrew: `brew install ffmpeg webp`). Run it again when iOS changes
# the assets:
#
#     sh scripts/convert_level_change.sh [path/to/proximiio-gadkit-ios]
set -eu
export LC_ALL=C

ROOT=$(cd "$(dirname "$0")/.." && pwd)
IOS=${1:-/Volumes/ExtremeSSD/proximiio/guggenheim-ios/Packages/ProximiioGADKit}
SOURCE="$IOS/Sources/ProximiioGADKit/Resources/LevelChange"
OUT="$ROOT/proximiio-gadkit/src/main/res/raw"
LAST="$ROOT/proximiio-gadkit/src/main/res/drawable-nodpi"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT" "$LAST"

for direction in up down; do
    for language in en ar; do
        input="$SOURCE/level-$direction-$language.apng"
        frames="$WORK/$direction-$language"
        mkdir -p "$frames"
        ffmpeg -v error -i "$input" -fps_mode passthrough -pix_fmt rgba "$frames/%03d.png"
        # Frame durations in ms, in frame order.
        ffprobe -v error -select_streams v:0 -show_entries frame=duration_time -of csv=p=0 "$input" \
            | awk '{ printf "%d\n", $1 * 1000 + 0.5 }' > "$frames/durations"
        set --
        index=1
        while read -r duration; do
            set -- "$@" -d "$duration" "$(printf '%s/%03d.png' "$frames" "$index")"
            index=$((index + 1))
        done < "$frames/durations"
        img2webp -loop 1 -lossless -exact -m 6 "$@" -o "$OUT/level_${direction}_${language}.webp" > /dev/null
        cp "$(printf '%s/%03d.png' "$frames" "$((index - 1))")" "$LAST/level_${direction}_${language}_last.png"
        if [ "$direction" = up ] && [ "$language" = en ]; then cp "$frames/001.png" "$LAST/level_up_first.png"; fi
        echo "level_${direction}_${language}.webp: $((index - 1)) frames"
    done
done
