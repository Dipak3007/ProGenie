import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { marked } from 'marked';

/**
 * Renders Markdown (policy texts). The HTML goes through Angular's [innerHTML] sanitiser, which strips scripts,
 * event handlers and unsafe URLs, so admin-written text can't inject code.
 */
@Component({
  selector: 'pg-markdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `<div class="md" [innerHTML]="html()"></div>`,
})
export class Markdown {
  readonly text = input<string | null | undefined>('');
  protected readonly html = computed(() => marked.parse(this.text() ?? '', { async: false, gfm: true, breaks: false }) as string);
}
