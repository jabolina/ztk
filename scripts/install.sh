#!/bin/sh
# Installs or upgrades ztk from its GitHub releases, with shell completion.
#
# Usage: curl -fsSL https://raw.githubusercontent.com/jabolina/ztk/main/scripts/install.sh | sh
#
# Environment:
#   ZTK_VERSION   the release tag to install, e.g. v1.0.0; the latest release when not set
#   ZTK_DIR       where to install; ~/.ztk when not set
#   ZTK_RELEASES  where the releases are; the GitHub releases page when not set
#
# Each release holds one binary per platform, named ztk-<os>-<arch>, next to its checksum, ztk-<os>-<arch>.sha256:
# ztk-linux-x86_64, ztk-linux-aarch64 and ztk-macos-aarch64. GraalVM no longer ships for Intel Macs, so neither does ztk.
# Running the script again replaces the binary, which upgrades ztk.
#
# Written for POSIX sh, so it runs piped into sh, bash or zsh.
set -eu

ZTK_DIR=${ZTK_DIR:-"$HOME/.ztk"}
ZTK_RELEASES=${ZTK_RELEASES:-"https://github.com/jabolina/ztk/releases"}
INIT_SCRIPT="$ZTK_DIR/init.sh"

fail() {
   printf 'ztk install failed: %s\n' "$1" >&2
   exit 1
}

step() {
   printf ' > %s\n' "$1"
}

platform() {
   case "$(uname -s)" in
      Linux) os=linux ;;
      Darwin) os=macos ;;
      *) fail "$(uname -s) has no ztk release. Build it from source: https://github.com/jabolina/ztk#build" ;;
   esac

   case "$(uname -m)" in
      x86_64 | amd64) arch=x86_64 ;;
      aarch64 | arm64) arch=aarch64 ;;
      *) fail "$(uname -m) processors have no ztk release. Build it from source: https://github.com/jabolina/ztk#build" ;;
   esac

   if [ "$os" = macos ] && [ "$arch" = x86_64 ]; then
      # A terminal running under Rosetta reports x86_64 on Apple silicon, which runs the aarch64 binary.
      if [ "$(sysctl -n hw.optional.arm64 2> /dev/null)" = 1 ]; then
         arch=aarch64
      else
         fail "ztk has no release for Intel Macs, since GraalVM, which builds it, no longer supports them."
      fi
   fi

   printf 'ztk-%s-%s' "$os" "$arch"
}

download() {
   # $1: the URL; $2: the file to write.
   if command -v curl > /dev/null 2>&1; then
      curl -fsSL -o "$2" "$1"
   elif command -v wget > /dev/null 2>&1; then
      wget -q -O "$2" "$1"
   else
      fail "neither curl nor wget is installed. Install one of them, then run this script again."
   fi
}

sha256() {
   if command -v sha256sum > /dev/null 2>&1; then
      sha256sum "$1" | cut -d ' ' -f 1
   elif command -v shasum > /dev/null 2>&1; then
      shasum -a 256 "$1" | cut -d ' ' -f 1
   else
      fail "neither sha256sum nor shasum is installed, so the download cannot be verified."
   fi
}

install_binary() {
   asset=$(platform)
   if [ -n "${ZTK_VERSION:-}" ]; then
      url="$ZTK_RELEASES/download/$ZTK_VERSION/$asset"
   else
      url="$ZTK_RELEASES/latest/download/$asset"
   fi

   step "Downloading $url"
   mkdir -p "$ZTK_DIR/bin"
   temporary=$(mktemp -d)
   trap 'rm -rf "$temporary"' EXIT
   download "$url" "$temporary/ztk" || fail "could not download $url. Check that the release exists for your platform."
   download "$url.sha256" "$temporary/ztk.sha256" || fail "could not download $url.sha256."

   expected=$(cut -d ' ' -f 1 < "$temporary/ztk.sha256")
   actual=$(sha256 "$temporary/ztk")
   [ "$expected" = "$actual" ] || fail "the download's checksum is $actual, but the release says $expected. Run this script again."

   chmod 755 "$temporary/ztk"
   # Moved in one step, so a ztk running meanwhile keeps its own file.
   mv -f "$temporary/ztk" "$ZTK_DIR/bin/ztk"
   step "Installed $ZTK_DIR/bin/ztk"
}

install_completion() {
   mkdir -p "$ZTK_DIR/completions"
   "$ZTK_DIR/bin/ztk" --aesh-completion zsh > "$ZTK_DIR/completions/_ztk"
   "$ZTK_DIR/bin/ztk" --aesh-completion bash > "$ZTK_DIR/completions/ztk.bash"

   # fish loads completions from its own folder, with no init script.
   if command -v fish > /dev/null 2>&1; then
      fish_completions="${XDG_CONFIG_HOME:-$HOME/.config}/fish/completions"
      mkdir -p "$fish_completions"
      "$ZTK_DIR/bin/ztk" --aesh-completion fish > "$fish_completions/ztk.fish"
   fi
   step "Generated the shell completion"
}

write_init_script() {
   # Rewritten on every run, so it always matches ZTK_DIR.
   cat > "$INIT_SCRIPT" << EOF
# Puts ztk on the PATH and enables its completion. Sourced by the shell's startup file.
export ZTK_DIR="$ZTK_DIR"
case ":\$PATH:" in
   *":\$ZTK_DIR/bin:"*) ;;
   *) export PATH="\$ZTK_DIR/bin:\$PATH" ;;
esac

if [ -n "\${ZSH_VERSION:-}" ]; then
   # In eval, so shells without arrays can still read this file.
   eval 'fpath=("\$ZTK_DIR/completions" \$fpath)'
   # Sourced after compinit, the new fpath entry is not read, so register the completion now.
   if whence compdef > /dev/null 2>&1; then
      autoload -Uz _ztk
      compdef _ztk ztk
   fi
elif [ -n "\${BASH_VERSION:-}" ]; then
   . "\$ZTK_DIR/completions/ztk.bash"
fi
EOF
}

configure_shell() {
   # The user's shell, not the one running this script: piped into sh, it is never zsh.
   case "$(basename "${SHELL:-}")" in
      zsh) rc="${ZDOTDIR:-$HOME}/.zshrc" ;;
      bash) rc="$HOME/.bashrc" ;;
      fish)
         step "For fish, add ztk to the PATH once: fish_add_path $ZTK_DIR/bin"
         return
         ;;
      *)
         step "Add this line to your shell's startup file: . \"$INIT_SCRIPT\""
         return
         ;;
   esac

   if [ -f "$rc" ] && grep -qF "$INIT_SCRIPT" "$rc"; then
      step "$rc already loads ztk"
      return
   fi

   printf '\n# ztk\n[ -s "%s" ] && . "%s"\n' "$INIT_SCRIPT" "$INIT_SCRIPT" >> "$rc"
   step "Added ztk to $rc"
}

printf 'Installing ztk into %s\n' "$ZTK_DIR"
install_binary
install_completion
write_init_script
configure_shell
printf '\nztk is installed. Open a new terminal, then run: ztk --help\n'
printf 'ztk needs Taskwarrior 3 and a configuration: https://github.com/jabolina/ztk/blob/main/docs/manual/how-to/install.adoc\n'
