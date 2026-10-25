# shellcheck shell=bash disable=SC2034  # sets variables for the script that sources it
# Shared by render-env.sh and supabase-migrate.sh: turns the Supabase "Session pooler" connection string into
# JDBC settings. Sourced, not run.
#
# Input:  SUPABASE_URL, e.g. postgresql://postgres.abcdefgh:PASSWORD@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres
# Output: DB_JDBC_URL, DB_USER, DB_PASSWORD, and for Supabase Storage SUPABASE_REF (project ref) and SUPABASE_REGION
parse_supabase_url() {
  local parsed
  parsed=$(python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit, unquote
raw = sys.argv[1].strip()
if "YOUR-PASSWORD" in raw.upper() or "[" in raw:
    sys.exit("Put your real database password into the connection string (replace [YOUR-PASSWORD], brackets too)")
try:
    u = urlsplit(raw)
    ok = u.scheme in ("postgresql", "postgres") and u.hostname and u.username and u.password is not None
    port = u.port
except ValueError:
    ok = False
if not ok:
    sys.exit("Not a postgresql://user:password@host:5432/postgres connection string. If your password has symbols "
             "such as @ / # ?, reset it to letters and digits only (Project Settings -> Database)")
if port == 6543:
    sys.exit("That is the Transaction pooler (port 6543). Copy the Session pooler string (port 5432) instead")
db = (u.path or "/postgres").lstrip("/") or "postgres"
print(f"jdbc:postgresql://{u.hostname}:{port or 5432}/{db}?sslmode=require&stringtype=unspecified")
print(unquote(u.username))
print(unquote(u.password))
import re
user = unquote(u.username)
print(user.split(".", 1)[1] if "." in user else "")
m = re.match(r"aws-\d+-([a-z0-9-]+)\.pooler\.supabase\.com$", u.hostname or "")
print(m.group(1) if m else "")
PY
) || return 1
  DB_JDBC_URL=$(sed -n 1p <<< "$parsed")
  DB_USER=$(sed -n 2p <<< "$parsed")
  DB_PASSWORD=$(sed -n 3p <<< "$parsed")
  SUPABASE_REF=$(sed -n 4p <<< "$parsed")
  SUPABASE_REGION=$(sed -n 5p <<< "$parsed")
  if [ ${#DB_PASSWORD} -lt 12 ]; then
    echo "The database password must be at least 12 characters (the API refuses shorter ones on a server)." >&2
    echo "Reset it in Supabase: Project Settings → Database → Reset database password." >&2
    return 1
  fi
}
