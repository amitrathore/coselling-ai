# Coselling.ai

Self-contained static redesign of [Coselling.ai](https://coselling.ai), built for GitHub Pages.

## Local preview

```bash
python3 -m http.server 8000
```

Then open `http://localhost:8000`.

## Content and media

- The homepage preserves the published Coselling copy and original imagery in a new responsive layout.
- Secondary marketing, company, contact, and policy pages are generated from the captured WordPress page export.
- All site images, illustrations, icons, styles, and first-party scripts are stored in this repository. The contact page loads its lead form from Tally.
- `tools/import_site.py` refreshes the captured WordPress export and media archive.
- `tools/generate_pages.py` regenerates secondary static pages from that export.
- `tools/create_tally_lead_form.rb` idempotently provisions the published lead form when `TALLY_SO_API_KEY` is set.

## Deployment

GitHub Pages serves the repository root. `.nojekyll` prevents Jekyll processing.
