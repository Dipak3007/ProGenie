#!/usr/bin/env bash
# Nightly SIT backup: a PostgreSQL dump and the uploaded files (KYC documents, ticket photos, receipts, exports).
# Cron (as the deploy user):  15 2 * * *  ~/progenie/ProGenieV2/infra/scripts/sit-backup.sh >> ~/progenie-backup.log 2>&1
# Restore steps are in docs/DEPLOYMENT.md.
set -euo pipefail
cd "$(dirname "$0")/.."

backup_dir=${BACKUP_DIR:-$HOME/backups/progenie}
keep_days=${KEEP_DAYS:-7}
db_user=$(grep -E '^POSTGRES_USER=' .env.sit | cut -d= -f2- | tr -d "'\"")
db_name=$(grep -E '^POSTGRES_DB=' .env.sit | cut -d= -f2- | tr -d "'\"")
stamp=$(date +%F-%H%M)

mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
docker exec progenie-postgres pg_dump -U "${db_user:-progenie}" -d "${db_name:-progenie}" -Fc > "$backup_dir/db-$stamp.dump"
docker run --rm -v progenie_uploads:/data:ro -v "$backup_dir":/backup alpine \
  tar czf "/backup/uploads-$stamp.tgz" -C /data .
find "$backup_dir" -type f \( -name 'db-*.dump' -o -name 'uploads-*.tgz' \) -mtime +"$keep_days" -delete
echo "$(date '+%F %T') backup ok: $(du -sh "$backup_dir" | cut -f1) in $backup_dir"
