# Coselling.ai

[Coselling.ai](https://coselling.ai) as an Intergraph game: the `coselling-ai` Game Master serves the site and runs the Coselling.ai coseller network on MoM, with the same six protocols as The J's (attention, market, coseller, withdrawal, introspection, avatar).

## Layout

- `resources/site/` — the website, served unchanged at the URLs it had on GitHub Pages (`/`, `/pages/<slug>/`, `/assets/…`). These hand-edited pages are the content of record.
- `resources/site/script.js` — besides the site's own behavior, the network layer every page loads: it records `?ref=` visits, carries sign-in, and gives cosellers a "Share this page" button.
- `resources/site/pages/join/` and `pages/share/` — become a coseller and get your share link (also at `/join` and `/share`).
- `src/coselling_ai/spec.clj` — the game spec: protocols, roles, market taxonomy, coseller policy.
- `src/coselling_ai/handlers.clj` — the MoM webhook, identity and visitor routes, and the static site.
- `content/` and `tools/` — the original WordPress import. **Do not rerun `tools/generate_pages.py`**: the pages have been edited by hand since, and regenerating them would overwrite those edits.

## Selling the offer

The Launch Your Network offer is two Market listings, defined in `spec.clj` (`offer-listings`): the one-time setup fee and the monthly platform subscription. They are bought in two checkouts, because Market refuses one that mixes a one-time price with a recurring one. `coselling-ai.marketplace` is the payments boundary (Stripe checkout and webhooks), ported from agents-of-mind.

The listings belong to the `coselling` seller player, not to the GM. Create that player and declare the listings with:

```bash
SELLER_OWNER_ID=<MoM user-id that owns the seller> lein run -m coselling-ai.listings
```

It reads the same environment as the GM and is safe to rerun. Checkout stays unavailable, and the offer page keeps its contact link, until `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` and `STRIPE_WEBHOOK_SECRET` are set and the game is onboarded as a merchant in Portal. `MARKET_PAYMENT_PROVIDER=stub` runs the whole flow locally without a processor.

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
