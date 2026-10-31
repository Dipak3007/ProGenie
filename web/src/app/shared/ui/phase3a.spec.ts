import { TestBed } from '@angular/core/testing';

import { GenieBadges } from './genie-badges';
import { editableStages } from './job-photos';
import { lastMonths } from './statement-download';

describe('job photos', () => {
  it('lets the Genie change photos only while the job allows it', () => {
    expect(editableStages('IN_PROGRESS', null)).toEqual(['BEFORE', 'AFTER']);
    expect(editableStages('ACCEPTED', null)).toEqual([]);
    const anHourAgo = new Date(Date.now() - 3600_000).toISOString();
    const twoDaysAgo = new Date(Date.now() - 48 * 3600_000).toISOString();
    expect(editableStages('COMPLETED', anHourAgo)).toEqual(['AFTER']);
    expect(editableStages('COMPLETED', twoDaysAgo)).toEqual([]);
  });
});

describe('earnings statement months', () => {
  it('lists this month (so far) and the previous ones, across a year end', () => {
    const months = lastMonths(3, new Date(2026, 0, 15));
    expect(months.map((m) => m.value)).toEqual(['2026-01', '2025-12', '2025-11']);
    expect(months[0].label).toContain('(so far)');
    expect(months[1].label).not.toContain('(so far)');
  });
});

describe('GenieBadges', () => {
  it('shows known badges with their meaning and skips unknown codes', () => {
    const fixture = TestBed.createComponent(GenieBadges);
    fixture.componentRef.setInput('badges', ['TOP_GENIE', 'SOMETHING_ELSE', 'NEW']);
    fixture.componentRef.setInput('explain', true);
    fixture.detectChanges();
    const items = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('li'));
    expect(items.map((li) => li.textContent?.trim().split(':')[0].trim())).toEqual(['Top Genie', 'New']);
    expect(items[0].getAttribute('title')).toContain('4.8');
  });

  it('renders nothing without badges', () => {
    const fixture = TestBed.createComponent(GenieBadges);
    fixture.componentRef.setInput('badges', null);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('ul')).toBeNull();
  });
});
