#!/bin/sh
# Installs axl for the current user, without root: into ~/.local/share/axolotl, with axl in ~/.local/bin.
set -e

here="$(cd "$(dirname "$0")" && pwd)"
prefix="${AXL_HOME:-$HOME/.local/share/axolotl}"
bin="$HOME/.local/bin"

rm -rf "$prefix"
mkdir -p "$prefix" "$bin"
cp -R "$here/axl/." "$prefix/"
ln -sf "$prefix/bin/axl" "$bin/axl"
"$prefix/bin/axl" setup

case ":$PATH:" in
    *":$bin:"*)
        echo "axl is installed: run 'axl --help'."
        ;;
    *)
        echo "axl is installed in $bin, which is not on your PATH yet. Add this line to ~/.profile:"
        echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        ;;
esac
