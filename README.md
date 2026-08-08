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
- All site images, illustrations, icons, styles, and scripts are stored in this repository. The deployed site has no externally hosted runtime assets.
- `tools/import_site.py` refreshes the captured WordPress export and media archive.
- `tools/generate_pages.py` regenerates secondary static pages from that export.

## Deployment

GitHub Pages serves the repository root. `.nojekyll` prevents Jekyll processing.
