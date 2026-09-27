import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { AvailabilityEditor } from './availability-editor';

describe('AvailabilityEditor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  async function render(windows: { dayOfWeek: number; startTime: string; endTime: string }[]) {
    const fixture = TestBed.createComponent(AvailabilityEditor);
    fixture.componentRef.setInput('windows', windows);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the weekly summary for valid hours', async () => {
    const el = await render([
      { dayOfWeek: 1, startTime: '09:00:00', endTime: '13:00:00' },
      { dayOfWeek: 1, startTime: '14:00:00', endTime: '18:00:00' },
      { dayOfWeek: 2, startTime: '09:00:00', endTime: '17:00:00' },
    ]);
    expect(el.textContent).toContain('2 working days · 16 hours a week');
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });

  it('flags overlapping windows before they are sent to the API', async () => {
    const el = await render([
      { dayOfWeek: 3, startTime: '09:00', endTime: '13:00' },
      { dayOfWeek: 3, startTime: '12:00', endTime: '15:00' },
    ]);
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Wednesday: two windows overlap');
  });

  it('requires quarter-hour times', async () => {
    const el = await render([{ dayOfWeek: 5, startTime: '09:10', endTime: '12:00' }]);
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('quarter hour');
  });
});
