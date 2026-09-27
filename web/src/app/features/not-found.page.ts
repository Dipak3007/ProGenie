import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'pg-not-found-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page grid min-h-[60dvh] place-items-center py-24 text-center">
      <div>
        <p class="font-display text-7xl font-extrabold text-gold">404</p>
        <h1 class="mt-4 text-3xl font-bold">Even a Genie can't find this page</h1>
        <a routerLink="/" class="btn-primary mt-8">Back to home</a>
      </div>
    </div>
  `,
})
export default class NotFoundPage {}
