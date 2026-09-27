# Images

Photos reused from the original ProGenie (JSP) project, resized and converted to WebP.

| Folder | Used for |
| --- | --- |
| `categories/` | Category cover photos, `<slug>.webp`. Set per category in the database (`service_categories.image_url`, migration V3). |
| `hero/` | Home page collage, "Why ProGenie", "Become a Genie", login/sign-up panels. |
| `people/` | Small portrait stack in the home hero ("Verified professionals"). |
| `coming-soon/` | Elderly / child / pet care teaser cards. |

**Before a public launch:** confirm you have the rights to every photo (their original sources
weren't recorded in the old project), or replace them with your own or properly licensed stock
photos, and swap the portraits for real ProGenie Genies who have agreed to be shown.

To add a category photo: save an 800×600 WebP as `categories/<slug>.webp`, then add a migration
that sets `image_url = '/images/categories/<slug>.webp'` for that category.
