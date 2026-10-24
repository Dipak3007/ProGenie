"""Keeps the SIT data of the Hugging Face Space in a private Hugging Face dataset.

A free Space has no persistent disk: every restart, rebuild or wake-up from sleep starts with an empty
container. start.sh therefore calls this script to:

  restore  once at start-up: download the latest database dump and uploaded files (exit 0),
           or report that no backup exists yet (exit 3). Any other failure exits 1, and start.sh
           stops rather than starting with an empty database that the next backup would save over
           the good one.
  loop     every BACKUP_INTERVAL seconds: back up, but only when rows or uploaded files changed.
  now      one backup immediately (start.sh runs it when the container is stopped).

Files in the dataset:
  latest/db.dump, latest/uploads.tar.gz        the newest backup (overwritten each time)
  daily/<Mon..Sun>/db.dump, uploads.tar.gz     the first backup of each day, so a week of history is kept
  daily/<Mon..Sun>/date.txt                    which date that copy is from
Every backup is a commit, so once the dataset has more than 100 commits its history is squashed to one
commit (the files stay; only old versions go) and the repository doesn't grow forever.

Settings (environment): BACKUP_DATASET (e.g. dipak/progenie-sit-data), HF_TOKEN (write access to it),
BACKUP_INTERVAL (seconds, default 600). BACKUP_DIR=/some/folder stores backups in a local folder instead
(used for testing). Database connection: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
"""

import datetime
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time

LATEST = "latest"
DB_FILE = "db.dump"
UPLOADS_FILE = "uploads.tar.gz"
NO_BACKUP_YET = 3
MAX_COMMITS = 100

UPLOADS_DIR = os.environ.get("UPLOADS_DIR", os.path.expanduser("~/data/uploads"))
INTERVAL = int(os.environ.get("BACKUP_INTERVAL", "600"))


def log(message):
    print(f"[backup] {message}", flush=True)


# ---------------------------------------------------------------- where backups are kept

class HfDataset:
    """A private Hugging Face dataset repository."""

    def __init__(self, repo):
        from huggingface_hub import HfApi

        self.repo = repo
        self.token = os.environ.get("HF_TOKEN") or None
        self.api = HfApi(token=self.token)

    def fetch(self, path, dest):
        """Downloads path into dest; returns False if the file doesn't exist in the dataset."""
        from huggingface_hub import hf_hub_download
        from huggingface_hub.errors import EntryNotFoundError

        try:
            # force_download matters: without it a network error could fall back to "not cached = not found",
            # and the Space would start empty and then save that over the real backup.
            got = hf_hub_download(self.repo, path, repo_type="dataset", token=self.token,
                                  local_dir=os.path.dirname(dest) + "/hf", force_download=True)
        except EntryNotFoundError:
            return False
        shutil.move(got, dest)
        return True

    def store(self, files, message):
        """files: {path in dataset: local file}. One commit for all of them."""
        from huggingface_hub import CommitOperationAdd

        ops = [CommitOperationAdd(path_in_repo=p, path_or_fileobj=f) for p, f in files.items()]
        self.api.create_commit(self.repo, ops, commit_message=message, repo_type="dataset")
        if len(self.api.list_repo_commits(self.repo, repo_type="dataset")) > MAX_COMMITS:
            self.api.super_squash_history(self.repo, repo_type="dataset", commit_message="Squash backup history")
            log("squashed the dataset history")


class LocalFolder:
    """A folder on disk (for testing the scripts without a Hugging Face account)."""

    def __init__(self, folder):
        self.folder = folder

    def fetch(self, path, dest):
        src = os.path.join(self.folder, path)
        if not os.path.exists(src):
            return False
        shutil.copyfile(src, dest)
        return True

    def store(self, files, message):
        for path, local in files.items():
            target = os.path.join(self.folder, path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(local, target + ".tmp")
            os.replace(target + ".tmp", target)


def storage():
    if os.environ.get("BACKUP_DIR"):
        return LocalFolder(os.environ["BACKUP_DIR"])
    if os.environ.get("BACKUP_DATASET"):
        return HfDataset(os.environ["BACKUP_DATASET"])
    return None


# ---------------------------------------------------------------- database and files

def psql(sql):
    out = subprocess.run(["psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql],
                         check=True, capture_output=True, text=True)
    return out.stdout.strip()


def fingerprint():
    """Changes whenever a row is inserted, updated or deleted, or an uploaded file is added or changed."""
    rows = psql("SELECT coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) FROM pg_stat_user_tables")
    files = []
    for root, _, names in os.walk(UPLOADS_DIR):
        for name in names:
            st = os.stat(os.path.join(root, name))
            files.append((os.path.join(root, name), st.st_size, int(st.st_mtime)))
    return rows, hash(tuple(sorted(files)))


def make_backup(workdir):
    dump = os.path.join(workdir, DB_FILE)
    subprocess.run(["pg_dump", "-Fc", "--no-owner", "--no-privileges", "-f", dump], check=True)
    uploads = os.path.join(workdir, UPLOADS_FILE)
    os.makedirs(UPLOADS_DIR, exist_ok=True)
    with tarfile.open(uploads, "w:gz") as tar:
        tar.add(UPLOADS_DIR, arcname=".")
    return dump, uploads


def daily_done(store, workdir, day, today):
    """True if daily/<day> already holds today's copy (written by this or an earlier run of the container)."""
    marker = os.path.join(workdir, "date.txt")
    if not store.fetch(f"daily/{day}/date.txt", marker):
        return False
    with open(marker) as f:
        return f.read().strip() == today


def backup(store, state):
    """One backup. state remembers the date already copied to daily/."""
    with tempfile.TemporaryDirectory() as workdir:
        before = fingerprint()
        dump, uploads = make_backup(workdir)
        files = {f"{LATEST}/{DB_FILE}": dump, f"{LATEST}/{UPLOADS_FILE}": uploads}
        today = datetime.date.today().isoformat()
        day = datetime.date.today().strftime("%a")
        if state.get("daily") != today and not daily_done(store, workdir, day, today):
            marker = os.path.join(workdir, "date.txt")
            with open(marker, "w") as f:
                f.write(today + "\n")
            files[f"daily/{day}/{DB_FILE}"] = dump
            files[f"daily/{day}/{UPLOADS_FILE}"] = uploads
            files[f"daily/{day}/date.txt"] = marker
        size = os.path.getsize(dump) + os.path.getsize(uploads)
        store.store(files, f"Backup {datetime.datetime.now(datetime.UTC):%Y-%m-%d %H:%M} UTC")
        state["daily"] = today
        state["fingerprint"] = before
        log(f"saved ({size // 1024} KB)")


# ---------------------------------------------------------------- commands

def cmd_restore(dest):
    store = storage()
    if store is None:
        return NO_BACKUP_YET
    os.makedirs(dest, exist_ok=True)
    for attempt in range(1, 4):   # ride out a short network or Hugging Face hiccup at start-up
        try:
            found = store.fetch(f"{LATEST}/{DB_FILE}", os.path.join(dest, DB_FILE))
            break
        except Exception as e:
            if attempt == 3:
                raise
            log(f"download failed ({e}); retrying in 15 s")
            time.sleep(15)
    if not found:
        return NO_BACKUP_YET
    if not store.fetch(f"{LATEST}/{UPLOADS_FILE}", os.path.join(dest, UPLOADS_FILE)):
        log("the backup has a database dump but no uploads archive; continuing with the database only")
    return 0


def cmd_now():
    store = storage()
    if store is None:
        return 0
    backup(store, {})
    return 0


def cmd_loop():
    store = storage()
    if store is None:
        return 0
    state = {"fingerprint": fingerprint()}   # what was just restored doesn't need saving again
    failures = 0
    while True:
        time.sleep(INTERVAL)
        try:
            if fingerprint() != state["fingerprint"]:
                backup(store, state)
            failures = 0
        except Exception as e:  # keep the loop alive; the next round retries
            failures += 1
            log(f"FAILED ({failures} in a row): {e}")


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        if command == "restore":
            return cmd_restore(sys.argv[2])
        if command == "now":
            return cmd_now()
        if command == "loop":
            return cmd_loop()
    except Exception as e:
        log(f"{command} FAILED: {e}")
        return 1
    print("usage: backup.py restore <folder> | now | loop", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
