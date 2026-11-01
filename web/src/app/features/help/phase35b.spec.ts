import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { HelpArticle } from '../../core/api/models';
import { NotifyMe } from '../../shared/ui/notify-me';
import { ReportDialog } from '../support/report-dialog';
import HelpPage from './help.page';

const settle = () => new Promise((r) => setTimeout(r, 30));
const article = (id: number, category: HelpArticle['category'], question: string, answer = 'An answer.'): HelpArticle => ({
  id, category, slug: 'a-' + id, question, answer,
});

function setup<T>(type: Type<T>): { fixture: ComponentFixture<T>; http: HttpTestingController } {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  const fixture = TestBed.createComponent(type);
  return { fixture, http: TestBed.inject(HttpTestingController) };
}

/** The city list some components load on start is not what these tests are about. */
function ignoreCities(http: HttpTestingController): void {
  for (const r of http.match('/api/v1/cities')) r.flush([]);
}

describe('NotifyMe', () => {
  let fixture: ComponentFixture<NotifyMe>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;
  const contact = () => el().querySelector('input:not([type=checkbox])') as HTMLInputElement;
  const consent = () => el().querySelector('input[type=checkbox]') as HTMLInputElement;
  const send = () => Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes('Notify me'))!;

  beforeEach(async () => {
    ({ fixture, http } = setup(NotifyMe));
    fixture.componentRef.setInput('pincode', '411038');
    fixture.detectChanges();
    ignoreCities(http);
    (el().querySelector('[data-testid=notify-open]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
  });
  afterEach(() => http.verify());

  async function fill(value: string, agree: boolean): Promise<void> {
    contact().value = value;
    contact().dispatchEvent(new Event('input'));
    if (agree) consent().click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('needs the consent tick before it sends', async () => {
    await fill('9876543210', false);
    expect(send().disabled).toBe(true);
    await fill('9876543210', true);
    expect(send().disabled).toBe(false);
  });

  it('sends a phone number for the PIN code and shows the masked contact', async () => {
    await fill('9876543210', true);
    send().click();
    const req = http.expectOne('/api/v1/launch-alerts');
    expect(req.request.body).toEqual({ pincode: '411038', phone: '9876543210', email: null, consent: true });
    req.flush({ pincode: '411038', contact: '******3210', channel: 'PHONE' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=notify-done]')?.textContent).toContain('******3210');
    expect(el().querySelector('[data-testid=notify-done]')?.textContent).toContain('411038');
  });

  it('sends an e-mail instead when one is typed, and shows a refusal', async () => {
    await fill('me@example.in', true);
    send().click();
    const req = http.expectOne('/api/v1/launch-alerts');
    expect(req.request.body.email).toBe('me@example.in');
    expect(req.request.body.phone).toBeNull();
    req.flush({ code: 'RATE_LIMITED', detail: 'Too many tries. Please wait a while.' }, { status: 429, statusText: 'Too Many' });
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Too many tries');
  });
});

describe('HelpPage', () => {
  let fixture: ComponentFixture<HelpPage>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;
  const articles = [
    article(1, 'BOOKING', 'How do I book a service?'),
    article(2, 'CANCELLATIONS', 'Can I cancel for free?', 'Yes, up to 2 hours before the slot.'),
    article(3, 'GENIES', 'How do payouts work?'),
  ];

  beforeEach(async () => {
    try {
      localStorage.removeItem('pg-help-voted');
    } catch {
      // storage blocked: nothing to clear
    }
    ({ fixture, http } = setup(HelpPage));
    fixture.detectChanges();
    await settle();
    http.expectOne('/api/v1/help').flush(articles);
    await fixture.whenStable();
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  const questions = () => Array.from(el().querySelectorAll('[data-testid=help-article] summary')).map((s) => s.textContent?.replace('+', '').trim());

  it('groups every answer under its category', () => {
    expect(questions()).toEqual(['How do I book a service?', 'Can I cancel for free?', 'How do payouts work?']);
    expect(Array.from(el().querySelectorAll('h2')).map((h) => h.textContent?.trim())).toEqual(['Booking', 'Cancellations', 'For Genies']);
  });

  it('searches questions and answers, and says when nothing matches', () => {
    const search = el().querySelector('#help-q') as HTMLInputElement;
    search.value = '2 hours';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(questions()).toEqual(['Can I cancel for free?']);
    search.value = 'warranty';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el().textContent).toContain('No answers match');
  });

  it('opens one category from the link (?category=GENIES)', () => {
    fixture.componentRef.setInput('category', 'GENIES');
    fixture.detectChanges();
    expect(questions()).toEqual(['How do payouts work?']);
  });

  it('counts a helpful vote once and thanks the reader', () => {
    const yes = Array.from(el().querySelectorAll('[data-testid=help-vote] button'))[0] as HTMLButtonElement;
    yes.click();
    http.expectOne((r) => r.url === '/api/v1/help/1/vote' && r.body.helpful === true).flush(null);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=help-vote]')?.textContent).toContain('Thanks for telling us');
  });
});

describe('ReportDialog suggestions', () => {
  let fixture: ComponentFixture<ReportDialog>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;
  const pick = (value: string) => {
    const radio = el().querySelector(`input[type=radio][value=${value}]`) as HTMLInputElement;
    radio.click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    // jsdom has no modal dialogs
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
    ({ fixture, http } = setup(ReportDialog));
    fixture.componentRef.setInput('open', true);
    fixture.componentRef.setInput('bookingId', 'b-1');
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  it('suggests answers for the chosen problem before the form', () => {
    pick('PAYMENT');
    http.expectOne((r) => r.url === '/api/v1/help/suggestions' && r.params.get('ticketCategory') === 'PAYMENT')
      .flush([article(7, 'PAYMENTS', 'When will my refund arrive?')]);
    fixture.detectChanges();
    const box = el().querySelector('[data-testid=help-suggestions]')!;
    expect(box.textContent).toContain('When will my refund arrive?');
    expect(box.textContent).toContain('This answered my question');
  });

  it('never stands between a safety report and the form', () => {
    pick('SAFETY');
    http.expectNone((r) => r.url === '/api/v1/help/suggestions');
    expect(el().querySelector('[data-testid=help-suggestions]')).toBeNull();
  });
});
