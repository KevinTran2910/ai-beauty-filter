# DOCX build tooling

This folder regenerates the packaged Word documents in `../dist/` from the
Markdown sources in `docs/`. It is build tooling, not documentation — read the
`.md` files under `docs/` instead.

## Contents

- `md2docx.js` — a small Markdown → DOCX converter (uses the `docx` npm package).
  Each manifest may set `font` (East-Asian-safe typeface) and a `palette` object
  to override the document colors.
- `manifest-en.json` — assembles the English `.docx` from the English Markdown
  files in `docs/`.
- `manifest-ja.json` — assembles the Japanese `.docx` from `ja-src/full-ja.md`.
- `ja-src/full-ja.md` — the Japanese translation of the full documentation set.
- `manifest-vi.json` — assembles the concise Vietnamese overview `.docx` from
  `vi-src/tongquan-vi.md`, using a blue/yellow palette that matches the Android
  overview document (`android/docs/DAYO_BeautyFilter_TongQuan_VI.docx`).
- `vi-src/tongquan-vi.md` — the Vietnamese overview (7 sections, iOS-accurate).

## Regenerate

Requires Node.js and the global `docx` package (`npm install -g docx`).

```bash
cd docs/_docx-build
export NODE_PATH="$(npm root -g)"   # so 'docx' resolves
node md2docx.js manifest-en.json    # writes ../dist/...-EN.docx
node md2docx.js manifest-ja.json    # writes ../dist/...-JA.docx
node md2docx.js manifest-vi.json    # writes ../dist/DAYO_BeautyFilter_TongQuan_iOS_VI.docx
```

## Keeping the documents in sync

The English `.docx` is built directly from the Markdown files under `docs/`, so
editing those files and re-running the English build keeps it current.

The Japanese `.docx` is built from `ja-src/full-ja.md`, which is a manual
translation. When the English Markdown changes, update `ja-src/full-ja.md` to
match, then rebuild.
