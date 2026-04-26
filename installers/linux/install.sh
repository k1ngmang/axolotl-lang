#!/bin/sh
# Installs axl for the current user, without root: into ~/.local/share/axolotl, with axl in ~/.local/bin.
# AXL_HOME chooses another directory.
set -e

here="$(cd "$(dirname "$0")" && pwd)"
prefix="${AXL_HOME:-$HOME/.local/share/axolotl}"
bin="$HOME/.local/bin"

case "$prefix" in
    /*) ;;
    *) prefix="$PWD/$prefix" ;;
esac

# The path of a directory that may not exist yet, with the links of its nearest existing parent resolved.
resolve() {
    dir="$1"
    rest=
    while [ ! -d "$dir" ]; do
        rest="/$(basename "$dir")$rest"
        dir="$(dirname "$dir")"
    done
    echo "$(cd "$dir" && pwd -P)$rest"
}

# Replacing the directory would delete the files to install, or copy them into themselves.
payload="$(cd "$here/axl" && pwd -P)"
target="$(resolve "$prefix")"
case "$target/" in
    "$payload"/*) overlap=yes ;;
esac
case "$payload/" in
    "$target"/*) overlap=yes ;;
esac
if [ -n "${overlap:-}" ]; then
    echo "install.sh: AXL_HOME $prefix overlaps the unpacked files in $here; choose another AXL_HOME" >&2
    exit 1
fi

# The directory is replaced as a whole, so it must be empty or hold an earlier installation.
if [ -e "$prefix" ] && [ -n "$(ls -A "$prefix")" ]; then
    if [ ! -x "$prefix/bin/axl" ] || [ ! -f "$prefix/lib/app/axl.cfg" ]; then
        echo "install.sh: $prefix is not empty and holds no earlier installation of axl; choose another AXL_HOME" >&2
        exit 1
    fi
    rm -rf "$prefix"
fi
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
