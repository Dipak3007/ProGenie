import { Injectable, signal } from '@angular/core';

/** Opens the app-wide "verify your mobile number" dialog (rendered once in the app shell). */
@Injectable({ providedIn: 'root' })
export class PhoneVerification {
  readonly isOpen = signal(false);

  open(): void {
    this.isOpen.set(true);
  }

  close(): void {
    this.isOpen.set(false);
  }
}
