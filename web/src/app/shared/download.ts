import { HttpErrorResponse } from '@angular/common/http';

/** Saves a downloaded file (e.g. an earnings statement fetched with the login token) under {@code fileName}. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/**
 * A failed blob request carries its JSON error (ProblemDetail, with the error ID) as a Blob. This turns it back
 * into a normal error so {@code errorMessage()} can show it.
 */
export async function readBlobError(err: unknown): Promise<unknown> {
  if (!(err instanceof HttpErrorResponse) || !(err.error instanceof Blob)) return err;
  try {
    const body = JSON.parse(await err.error.text());
    return new HttpErrorResponse({ error: body, headers: err.headers, status: err.status, statusText: err.statusText, url: err.url ?? undefined });
  } catch {
    return err;
  }
}
