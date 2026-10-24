#!/usr/bin/env bash
# Nightly backup on the Azure VM: a database dump and the uploaded files, kept for 7 days.
# Cron (crontab -e):  15 2 * * *  bash ~/progenie/ProGenie/infra/nocard/azure/backup.sh >> ~/progenie-backup.log 2>&1
# Copy them to your laptop now and then: scp -r azureuser@<api-host>:backups/progenie ~/progenie-sit-backups
set -euo pipefail
cd "$(dirname "$0")"
dir=${BACKUP_DIR:-$HOME/backups/progenie}
keep_days=${KEEP_DAYS:-7}
stamp=$(date +%F-%H%M)
mkdir -p "$dir"
chmod 700 "$dir"
docker compose exec -T postgres pg_dump -U progenie -d progenie -Fc > "$dir/db-$stamp.dump"
docker run --rm -v progenie-azure_uploads:/data:ro -v "$dir":/backup alpine tar czf "/backup/uploads-$stamp.tgz" -C /data .
find "$dir" -type f \( -name 'db-*.dump' -o -name 'uploads-*.tgz' \) -mtime +"$keep_days" -delete
echo "$(date '+%F %T') backup ok: $(du -sh "$dir" | cut -f1) in $dir"
