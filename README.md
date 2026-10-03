# Coselling.ai

[Coselling.ai](https://coselling.ai) as an Intergraph game: the `coselling-ai` Game Master serves the site and runs the Coselling.ai coseller network on MoM, with the same six protocols as The J's (attention, market, coseller, withdrawal, introspection, avatar).

## Layout

- `resources/site/` — the website, served unchanged at the URLs it had on GitHub Pages (`/`, `/pages/<slug>/`, `/assets/…`). These hand-edited pages are the content of record.
- `resources/site/script.js` — besides the site's own behavior, the network layer every page loads: it records `?ref=` visits, carries sign-in, and gives cosellers a "Share this page" button.
- `resources/site/pages/join/` and `pages/share/` — become a coseller and get your share link (also at `/join` and `/share`).
- `src/coselling_ai/spec.clj` — the game spec: protocols, roles, market taxonomy, coseller policy.
- `src/coselling_ai/handlers.clj` — the MoM webhook, identity and visitor routes, and the static site.
- `content/` and `tools/` — the original WordPress import. **Do not rerun `tools/generate_pages.py`**: the pages have been edited by hand since, and regenerating them would overwrite those edits.

## Run locally

Needs intergraph-compose's MoM on :8080, plus a `coselling-ai` game created in local Portal (:8081) with a credential issued for it. Put them in `.env.local` (gitignored):

```bash
GM_OWNER_ID=<Portal user-id that created the game>
MOM_GAME_TOKEN=<credential issued for coselling-ai>
PLAYER_GROUP_ID=COSELLING_AI_LOCAL_GROUP
```

Then:

```bash
scripts/run-local.sh   # http://localhost:8894
lein test
```

## Deployment

The GM runs on Fly.io as the app `coselling-ai` (`fly.toml`). flyctl authenticates with an app-scoped deploy token in `.envrc` (gitignored, loaded by direnv):

```bash
export FLY_API_TOKEN="<fly tokens create deploy -a coselling-ai>"
```

```bash
fly deploy    # builds the Dockerfile and replaces the running machine
fly logs      # the GM stops itself with exit 0 when MoM refuses its token; read the logs, not the exit code
```

`MOM_GAME_TOKEN` and `GM_OWNER_ID` are Fly secrets, from the `coselling-ai` game in production Portal. The GM reaches MoM at `https://mom.intergraph.ai`, and MoM calls it back at `https://coselling-ai.fly.dev`.

Until the cutover, coselling.ai is still served by GitHub Pages from `master`, so the GM must not be merged to `master` before DNS points at Fly.
