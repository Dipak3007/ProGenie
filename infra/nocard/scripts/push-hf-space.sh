#!/usr/bin/env bash
# Sends the backend's container recipe (infra/nocard/hf-space/) to your Hugging Face Space. Hugging Face then
# rebuilds it: it fetches the code from GitHub at the commit named here, builds the API and restarts the Space.
#
#   HF_TOKEN=hf_... HF_SPACE=<hf-user>/progenie-api APP_REPO=<github-user>/ProGenieV2 \
#   DB_REPO=<github-user>/ProGenieV2-Database bash infra/nocard/scripts/push-hf-space.sh
#
# APP_REF / DB_REF choose what to build: a branch (default main) or a commit SHA. The code must already be
# pushed to GitHub. A branch is turned into its current commit SHA when your git can read the repository, so a
# later rebuild on Hugging Face builds the same code. See docs/DEPLOYMENT_NOCARD.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)

token=${HF_TOKEN:?set HF_TOKEN to the progenie-deploy token (write access to the Space)}
space=${HF_SPACE:?set HF_SPACE, e.g. dipak/progenie-api}
app_repo=${APP_REPO:?set APP_REPO, e.g. dipak3007/ProGenieV2}
db_repo=${DB_REPO:?set DB_REPO, e.g. dipak3007/ProGenieV2-Database}
app_ref=${APP_REF:-main}
db_ref=${DB_REF:-main}
for value in "$space" "$app_repo" "$db_repo"; do
  [[ $value =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || { echo "Not an owner/name value: $value" >&2; exit 1; }
done
[[ $app_ref =~ ^[A-Za-z0-9._/-]+$ && $db_ref =~ ^[A-Za-z0-9._/-]+$ ]] || { echo "Bad APP_REF / DB_REF" >&2; exit 1; }

# Pin a branch to its commit (uses your normal GitHub login: SSH key or saved HTTPS credentials)
pin() {
  local repo=$1 ref=$2 sha
  if [[ $ref =~ ^[0-9a-f]{40}$ ]]; then echo "$ref"; return; fi
  for url in "git@github.com:$repo.git" "https://github.com/$repo.git"; do
    sha=$(GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -o BatchMode=yes" timeout 20 \
          git ls-remote "$url" "refs/heads/$ref" 2>/dev/null | cut -f1)
    if [ -n "$sha" ]; then echo "$sha"; return; fi
  done
  echo "Note: couldn't look up $repo $ref; the Space will build whatever $ref is when it builds." >&2
  echo "$ref"
}
app_ref=$(pin "$app_repo" "$app_ref")
db_ref=$(pin "$db_repo" "$db_ref")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# The token reaches git through GIT_ASKPASS, so it never appears in a command line or in .git/config
cat > "$work/askpass" <<'ASK'
#!/bin/sh
case "$1" in Username*) echo "$HF_USER" ;; *) echo "$HF_TOKEN" ;; esac
ASK
chmod 700 "$work/askpass"
export GIT_ASKPASS="$work/askpass" GIT_TERMINAL_PROMPT=0 HF_USER=${space%%/*} HF_TOKEN=$token
remote="https://huggingface.co/spaces/$space"

git clone -q "$remote" "$work/space" 2>/dev/null \
  || { echo "Could not clone the Space $space. Does it exist (Docker SDK), and can HF_TOKEN write to it?" >&2; exit 1; }
cd "$work/space"
find . -mindepth 1 -maxdepth 1 ! -name .git ! -name .gitattributes -exec rm -rf {} +
cp "$root"/infra/nocard/hf-space/{Dockerfile,README.md,start.sh,backup.py,Caddyfile} .
cat > DEPLOY_REF <<REF
# Written by infra/nocard/scripts/push-hf-space.sh: what the next build fetches from GitHub
APP_REPO=$app_repo
APP_REF=$app_ref
DB_REPO=$db_repo
DB_REF=$db_ref
DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
REF

git add -A
git -c user.name="ProGenie deploy" -c user.email="deploy@progenie.invalid" \
  commit -q -m "Deploy $app_repo@$app_ref"
git push -q "$remote" HEAD:main 2>/dev/null || { echo "Push to the Space failed. Does HF_TOKEN have write access?" >&2; exit 1; }
echo "Pushed. Hugging Face is rebuilding: https://huggingface.co/spaces/$space (Logs → Build, about 10 minutes)."
