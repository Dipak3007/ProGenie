import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { errorId, errorMessage } from './api';

const fail = (status: number, body: unknown, headers?: Record<string, string>) =>
  new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers ?? {}) });

describe('error messages and error IDs', () => {
  it('adds the error ID to unexpected errors', () => {
    const err = fail(500, { status: 500, code: 'INTERNAL_ERROR', detail: 'Something went wrong on our side.', errorId: 'K7Q2M9XF3T1A' });
    expect(errorId(err)).toBe('K7Q2M9XF3T1A');
    expect(errorMessage(err)).toBe('Something went wrong on our side. Error ID: K7Q2M9XF3T1A');
  });

  it('keeps business answers short', () => {
    const err = fail(409, { status: 409, code: 'SLOT_TAKEN', detail: 'That slot was just taken', errorId: 'K7Q2M9XF3T1A' });
    expect(errorMessage(err)).toBe('That slot was just taken');
  });

  it('uses the response header when the body has no ID, and the fallback text when it has no message', () => {
    const err = fail(502, 'Bad gateway', { 'X-Request-Id': '8f1c2a3b4c5d6e7f-SIN' });
    expect(errorMessage(err, 'Could not save.')).toBe('Could not save. Error ID: 8f1c2a3b4c5d6e7f-SIN');
  });

  it('says when the server cannot be reached', () => {
    expect(errorMessage(fail(0, null))).toContain('Cannot reach the server');
  });
});
