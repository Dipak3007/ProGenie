import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { ApiClient, errorMessage } from '../../core/api/api';

@Component({
  selector: 'pg-contact-section',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section id="contact" class="container-page py-16" aria-labelledby="contact-heading">
      <div class="grid gap-10 lg:grid-cols-2">
        <div>
          <h2 id="contact-heading" class="text-3xl font-bold sm:text-4xl">Talk to us</h2>
          <p class="mt-3 max-w-md text-muted">Questions, feedback or want ProGenie in your city? Send a message and we will reply within a day.</p>
        </div>

        <form class="card grid gap-4 p-6 sm:grid-cols-2 sm:p-8" [formGroup]="form" (ngSubmit)="submit()">
          <div>
            <label class="label" for="c-name">Name</label>
            <input id="c-name" class="field" formControlName="name" autocomplete="name" />
          </div>
          <div>
            <label class="label" for="c-email">Email</label>
            <input id="c-email" class="field" type="email" formControlName="email" autocomplete="email" />
          </div>
          <div class="sm:col-span-2">
            <label class="label" for="c-subject">Subject</label>
            <input id="c-subject" class="field" formControlName="subject" />
          </div>
          <div class="sm:col-span-2">
            <label class="label" for="c-message">Message</label>
            <textarea id="c-message" class="field min-h-32" formControlName="message"></textarea>
          </div>
          <div class="flex flex-col gap-3 sm:col-span-2 sm:flex-row sm:items-center">
            <button type="submit" class="btn-primary" [disabled]="form.invalid || sending()">
              {{ sending() ? 'Sending…' : 'Send message' }}
            </button>
            @if (sent()) {
              <p class="text-sm font-medium text-emerald-700" role="status">Thanks! Our team will get back to you soon.</p>
            }
            @if (error()) {
              <p class="text-sm text-red-700" role="alert">{{ error() }}</p>
            }
          </div>
        </form>
      </div>
    </section>
  `,
})
export class ContactSection {
  private readonly api = inject(ApiClient);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email]],
    subject: ['', [Validators.required, Validators.maxLength(150)]],
    message: ['', [Validators.required, Validators.maxLength(2000)]],
  });
  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    if (this.form.invalid) return;
    this.sending.set(true);
    this.error.set(null);
    this.api.sendContact(this.form.getRawValue()).subscribe({
      next: () => {
        this.sent.set(true);
        this.sending.set(false);
        this.form.reset();
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.sending.set(false);
      },
    });
  }
}
