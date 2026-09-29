#!/usr/bin/env bash
# Turn Compose Desktop's AppImage output (an application directory) into a single .AppImage.
# Run from the repository root after `./gradlew :desktop:packageAppImage`.
# Env: APPIMAGETOOL_URL, APPIMAGETOOL_SHA256 (the tool is verified before it runs; fails closed).
set -euo pipefail

app_dir="desktop/build/compose/binaries/main/app/Guillotine"
[[ -x "$app_dir/bin/Guillotine" ]] || { echo "No Compose app image at $app_dir" >&2; exit 1; }

work="desktop/build/appimage"
rm -rf "$work"
mkdir -p "$work" desktop/build/dist
appdir="$work/Guillotine.AppDir"
cp -a "$app_dir" "$appdir"

# The three things an AppDir needs at its root: an entry point, a desktop entry and an icon.
cat > "$appdir/AppRun" <<'RUN'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
exec "$here/bin/Guillotine" "$@"
RUN
chmod +x "$appdir/AppRun"
cat > "$appdir/guillotine.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Guillotine
Comment=An AI-powered non-linear video editor.
Exec=Guillotine
Icon=guillotine
Categories=AudioVideo;Video;
Terminal=false
DESKTOP
cp desktop/src/main/resources/icons/icon.png "$appdir/guillotine.png"

tool="$work/appimagetool"
curl -fsSL -o "$tool" "$APPIMAGETOOL_URL"
echo "$APPIMAGETOOL_SHA256  $tool" | sha256sum -c - || {
  echo "appimagetool checksum mismatch: upstream replaced the file; review it and update APPIMAGETOOL_SHA256." >&2
  exit 1
}
chmod +x "$tool"
# Runners have no FUSE; extract the tool instead of mounting it.
(cd "$work" && ./appimagetool --appimage-extract > /dev/null)

version="$(grep -E '^version=' desktop/build/generated/versionResource/guillotine-version.properties 2>/dev/null | cut -d= -f2 || true)"
out="desktop/build/dist/Guillotine-${version:-desktop}-x86_64.AppImage"
ARCH=x86_64 "$work/squashfs-root/AppRun" --no-appstream "$appdir" "$out"
echo "Built $out"
