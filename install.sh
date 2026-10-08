#!/usr/bin/env sh
# Installs the Migrax command line tool for the current user (macOS and Linux).
#
# Run from an extracted Migrax ZIP, or from the Migrax source folder after "mvn package":
#   sh install.sh
# Installs to ~/.local/share/migrax and links ~/.local/bin/migrax. No root needed.
# Override the locations with MIGRAX_INSTALL_DIR and MIGRAX_BIN_DIR.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INSTALL_DIR=${MIGRAX_INSTALL_DIR:-"$HOME/.local/share/migrax"}
BIN_DIR=${MIGRAX_BIN_DIR:-"$HOME/.local/bin"}
TEMP=""

cleanup() {
  if [ -n "$TEMP" ]; then rm -rf "$TEMP"; fi
}
trap cleanup EXIT

if [ -f "$HERE/lib/migrax.jar" ]; then
  SOURCE="$HERE"
else
  ZIP=$(ls -t "$HERE"/target/migrax-*.zip 2>/dev/null | head -n 1 || true)
  if [ -z "$ZIP" ]; then
    echo "No Migrax build found. Run 'mvn package' in $HERE first, or run this script from an extracted Migrax ZIP." >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is required to install from $ZIP." >&2
    exit 1
  fi
  TEMP=$(mktemp -d)
  unzip -q "$ZIP" -d "$TEMP"
  SOURCE=$(find "$TEMP" -mindepth 1 -maxdepth 1 -type d | head -n 1)
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"
cp -R "$SOURCE/bin" "$SOURCE/lib" "$INSTALL_DIR/"
for file in README.md install.ps1 install.sh; do
  if [ -f "$SOURCE/$file" ]; then cp "$SOURCE/$file" "$INSTALL_DIR/"; fi
done
chmod +x "$INSTALL_DIR/bin/migrax"
ln -sf "$INSTALL_DIR/bin/migrax" "$BIN_DIR/migrax"

echo "Installed Migrax to $INSTALL_DIR and linked $BIN_DIR/migrax"

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    echo
    echo "$BIN_DIR is not on your PATH. Add this line to ~/.bashrc, ~/.zshrc or ~/.profile:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    ;;
esac

if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo
  echo "Warning: Java was not found. Migrax needs Java 17 or newer: install a JDK and set JAVA_HOME." >&2
fi

echo
echo "Open a new terminal, go to your service folder, and run:"
echo "  migrax init"
echo "  migrax generate"
echo "  migrax migrate"
