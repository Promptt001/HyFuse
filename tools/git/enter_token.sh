#!/bin/bash
# T2 helper: store the GitHub fine-grained PAT without echoing it to
# the terminal or into any tracked file. Reads the token from stdin
# (hidden), writes it to ~/.git-credentials-hyfuse in git-credential
# format, and wires the credential helper for this repo only.
#
# Usage: bash tools/git/enter_token.sh
# Then:  git push origin main   (per HANDOVER.md §17 first-push procedure)
set -eu
CREDFILE="$HOME/.git-credentials-hyfuse"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

printf "Paste the fine-grained PAT (input hidden), then press Enter: " >&2
read -rs TOKEN </dev/tty || read -rs TOKEN
printf "\n" >&2
[ -n "$TOKEN" ] || { echo "ERROR: empty token"; exit 2; }
case "$TOKEN" in
  github_pat_*|ghp_*) : ;;
  *) echo "WARNING: token does not look like a GitHub PAT (github_pat_/ghp_ prefix)";;
esac

umask 077
printf "https://Promptt001:%s@github.com\n" "$TOKEN" > "$CREDFILE"
git -C "$REPO_ROOT" config credential.helper "store --file=$CREDFILE"
chmod 600 "$CREDFILE"
echo "Stored credential to $CREDFILE (chmod 600) and wired the helper."
echo "Next: run the §17 first-push procedure (git push origin main)."
