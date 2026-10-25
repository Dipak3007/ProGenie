#!/usr/bin/env node
// Saves the running API's OpenAPI document as web/openapi.json (the input of the generated Angular client).
//
//   API running on :8080, then from web/:  npm run api:update   (= this script + ng-openapi-gen)
//   API=http://localhost:8080 node scripts/update-openapi.mjs
//
// The file is committed. OpenApiContractIT fails when it no longer matches the API, and web-ci fails when the
// generated client (src/app/core/api/generated) no longer matches the file.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const base = (process.env.API ?? 'http://localhost:8080').replace(/\/$/, '');
const res = await fetch(`${base}/v3/api-docs`);
if (!res.ok) {
  console.error(`GET ${base}/v3/api-docs failed: HTTP ${res.status}. Is the API running?`);
  process.exit(1);
}
const doc = await res.json();
delete doc.servers; // the URL changes with the port; not part of the contract
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'web', 'openapi.json');
writeFileSync(out, JSON.stringify(doc, null, 2) + '\n');
console.log(`Saved ${Object.keys(doc.paths).length} paths and ${Object.keys(doc.components?.schemas ?? {}).length} schemas to web/openapi.json`);
