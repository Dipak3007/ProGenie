---
title: ProGenie API (SIT)
emoji: 🧰
colorFrom: indigo
colorTo: blue
sdk: docker
app_port: 7860
pinned: false
short_description: ProGenie test backend (API, database, Mailpit)
---

# ProGenie SIT backend

Test (SIT) backend of ProGenie, a home-services booking app built as a learning project.

- One container: PostgreSQL + PostGIS, the Spring Boot API, Mailpit and Caddy.
- This Space is private. Visitors use the Cloudflare address, whose proxy is the only way in; every other request gets 403.
- The source code is not stored here: the build fetches it from a private GitHub repository.
- Data is saved to a private dataset, so it survives restarts and sleep.

These files are copied from `infra/nocard/hf-space/` in the main repository by `infra/nocard/scripts/push-hf-space.sh`.
Edit them there, not here.
