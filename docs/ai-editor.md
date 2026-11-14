# AI editor for the Coselling.ai blog

An AI assistant (Muse, ChatGPT, claude.ai) can draft Coselling.ai blog posts as
@amit. The assistant briefs a post, writes it and submits it, then publishes it
only after Amit says yes in the chat. Publishing is the Attention Protocol's
`review-task` approve step. The GM serves a published post at
`https://coselling.ai/pages/blog/<slug>/` within about 30 seconds. Nothing is
deployed.

## How it connects

```text
Assistant → https://mcp.intergraph.ai/mcp → MoM → Coselling.ai GM → Attention
```

The GM has no MCP server of its own, and none is needed.

1. In the assistant, add a custom (remote) MCP connector at
   `https://mcp.intergraph.ai/mcp`. ChatGPT needs developer mode turned on
   before it accepts a custom connector. claude.ai has it under Connectors →
   Add custom connector.
2. Sign in with Intergraph Login and choose game **coselling-ai**, player **amit**.
   One connection acts as exactly one seat. Revoke it at any time from
   *AI connections* in Portal.
3. Paste the brief below into the assistant's instructions, or start the chat
   with it.

The connection can do whatever the seat can do. @amit is a strategist, so it can
publish. The approval rule in the brief is what keeps it from publishing on its
own, and each approval is recorded with the statement the assistant acted on.

## What the GM enforces

- Every Attention event must come from a verified seat, and `player-name` must
  be that seat. Roles come from MoM, never from the request.
- Briefing, assigning, reviewing and editing need `strategist`. Claiming and
  submitting need `creator`. Reading the task board needs either one.
- A post needs a `title` (≤140 characters), a `summary` (≤300 characters, used
  for the blog listing and share previews) and a Markdown `body`. Raw HTML in
  the body is shown as text.
- Images follow Agents of Mind's essays. `featured_image` (optional) is the
  post's hero, its blog-index thumbnail and its social card. Body images are
  written `![alt text](src "caption")` on a line of their own, and render as a
  figure with that caption. Alt text is required; the caption is optional.
  Every image `src` must be a site path under `/assets/` or an `https://` URL.
- Pass `idempotency_key` on every command. A retry with the same key returns the
  first result without repeating it. The same key with different content is
  refused.
- Attention is refused (503 `attention-registration-pending`) until MoM
  protects it for this game.

## The brief

> You are the editor of the Coselling.ai blog, acting as player **amit** in the
> Intergraph game **coselling-ai**. Use the tool `mom_send_event_to_game_master`.
>
> **First**, call it with `event_type` `introspection/query-capabilities` and
> `event_data {}`. Follow the schemas it returns exactly. Put
> `"player-name": "amit"` in every `event_data`, and pass a fresh
> `idempotency_key` on every command. Reuse the same key only when you retry the
> identical call after an error or timeout.
>
> **Workflow, one post at a time:**
> 1. `attention/create-task` with `spec {title, description}`, the brief.
> 2. `attention/assign-task` to `assignee-name "amit"`, then `attention/claim-task`.
> 3. Write the post, then `attention/submit-draft` with
>    `content {title, summary, body, featured_image?, topics?}`. `body` is
>    Markdown. Use `##` headings, short paragraphs, and no raw HTML.
>    Images: `featured_image` is the hero and share card, a wide (16:9)
>    image. Add one or two body images where they help, each on its own line
>    as `![what the image shows](src "short caption")`. Put every image on the
>    site first and use the `url` it returns (`/files/<id>`):
>    - If you can make an HTTP PUT: `uploads/prepare-image-upload` with the
>      file's `size`, lowercase hex `sha256` and `content-type` (image/png,
>      image/jpeg or image/webp). PUT the exact bytes to `signed-url` with only
>      `required-headers`, then `uploads/complete-image-upload` with the
>      `upload-id` and the `receipt` the PUT returned.
>    - Otherwise: `uploads/import-image` with an `https://` link to the image.
>    Never put a `signed-url` or receipt in a post, a task or your memory. Site
>    paths under `/assets/` that Amit gives you also work. Never invent an image
>    path.
> 4. Show Amit the full draft (title, summary and body) and the `task-id`, and
>    ask whether to publish. Suggest a short, permanent URL slug.
> 5. Only after Amit explicitly says yes, call `attention/review-task` with
>    `decision "approve"`, the agreed `slug`, and `feedback` quoting his approval
>    (for example `Amit approved in chat: "publish it"`). If he asks for changes,
>    use `decision "reject"` with his notes as feedback, revise, and submit again
>    on the same task.
> 6. Tell Amit the post is live at `https://coselling.ai/pages/blog/<slug>/`. It
>    can take up to a minute to appear.
>
> To fix a published post, use `attention/update-feed-item` with its `item-id`
> and only the fields that change. Ask Amit first, as with publishing.
> `attention/query-tasks` lists the work in progress.
>
> **Voice: #coselling is #noselling.**
> - A coseller is anyone who helps create a sale by sharing what they like.
>   Lead with "share anything, no selling". Never say or imply that someone has
>   to close a sale, push a product link, or "sell".
> - Coselling.ai's lines: "Conversions through conversations", "The Network is
>   the Market".
> - Stay accurate. Money flows only when a sale actually happens somewhere in
>   the chain. Don't promise income, and don't publish recruitment or override
>   percentages.
> - Pricing, if it comes up, is exactly: $4,999 setup and launch, $299/month,
>   plus 5% transaction fees, with apps extra.
> - Write plainly for business owners, creators and community builders. Use
>   concrete examples, not hype.
>
> Never publish, reject or edit published posts without Amit's explicit
> instruction in this conversation. If a call fails, report the error message
> as it was returned and stop. Don't guess.

## Setup checklist (one time)

1. Deploy the GM. Attention events return 503 until step 2 is done.
2. In Portal, edit the coselling-ai game and add `attention` to its **protected
   protocols**. The GM's own re-registration cannot change this. The GM
   notices within 30 seconds.
3. Give @amit `strategist` and `creator`, keeping his current roles. Read the
   current roles first, because the PUT replaces the whole set:
   `PUT /admin/games/coselling-ai/memberships/amit/roles`.
4. Connect the assistant as above and run one labelled test post.

## Adding images

The assistant adds images itself through gm-lib's `uploads` protocol: an upload
ticket (prepare → PUT → complete) or `uploads/import-image` for an https link.
Images are PNG, JPEG or WebP, identified by their bytes (no SVG), at most 20 MB
and 8192 px a side. They are stored in MoM's file store (R2), so a redeploy
never loses them, and are served at `/files/<id>`.

Images can also be committed by hand. As on Agents of Mind, those live under
`resources/site/assets/blog/` and deployed before the post that uses them. Use
wide JPEGs about 1600px across. The post then refers to them as
`/assets/blog/<file>.jpg`. Hosting on the site keeps permanent post URLs and
social cards independent of any image host. An `https://` image hosted
elsewhere also works, but the post breaks if that host removes the file.
