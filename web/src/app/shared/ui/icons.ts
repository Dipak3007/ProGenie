import {
  LucideBadgeCheck as BadgeCheck,
  LucideBug as Bug,
  LucideCctv as Cctv,
  LucideDroplets as Droplets,
  LucideDumbbell as Dumbbell,
  LucideHammer as Hammer,
  LucideLaptop as Laptop,
  LucideIconInput,
  LucidePaintRoller as PaintRoller,
  LucideScissors as Scissors,
  LucideSnowflake as Snowflake,
  LucideSparkles as Sparkles,
  LucideWashingMachine as WashingMachine,
  LucideWrench as Wrench,
  LucideZap as Zap,
} from '@lucide/angular';

/** Maps the icon name stored in service_categories.icon to a Lucide icon. */
const CATEGORY_ICONS: Record<string, LucideIconInput> = {
  zap: Zap,
  droplets: Droplets,
  hammer: Hammer,
  'paint-roller': PaintRoller,
  sparkles: Sparkles,
  snowflake: Snowflake,
  'washing-machine': WashingMachine,
  bug: Bug,
  laptop: Laptop,
  cctv: Cctv,
  scissors: Scissors,
  dumbbell: Dumbbell,
  'badge-check': BadgeCheck,
};

export function categoryIcon(name: string | null | undefined): LucideIconInput {
  return (name && CATEGORY_ICONS[name]) || Wrench;
}

/** Icon name per category slug, for places that only know the slug (e.g. Genie cards). */
const SLUG_ICONS: Record<string, string> = {
  electrician: 'zap',
  plumber: 'droplets',
  carpenter: 'hammer',
  painter: 'paint-roller',
  'home-cleaning': 'sparkles',
  'ac-service': 'snowflake',
  'appliance-repair': 'washing-machine',
  'pest-control': 'bug',
  'computer-repair': 'laptop',
  'home-security': 'cctv',
  'salon-at-home': 'scissors',
  'personal-trainer': 'dumbbell',
};

export function iconNameForSlug(slug: string): string | null {
  return SLUG_ICONS[slug] ?? null;
}
