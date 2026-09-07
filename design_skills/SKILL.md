---
name: ui-ux-pro-max
description: >-
  Portable UI/UX design intelligence for coding agents. Generates a
  product-specific design system (style, color, type, layout) and blocks
  generic AI aesthetics across web, mobile, and desktop interfaces. Use when
  planning, building, designing, reviewing, or improving UI, or when the user
  mentions AI slop, generic UI, purple gradients, or "looks like AI".
---

# UI/UX Pro Max

Design intelligence for the current project and its actual platform. This
skill is agent-agnostic and supports web, mobile, desktop, and native UI.
Use stack-specific guidance only when a matching dataset is available.

Run scripts from the directory containing this file. Prefer `python` on
Windows if `python3` is missing.

```bash
python3 --version || python --version
```

## When to use

Use for anything that changes how a feature looks, feels, moves, or is interacted with.

Skip for backend-only, API/DB, infra, or non-visual scripts.

## Workflow

### 1. Extract requirements

- Product type (SaaS, spa, fintech, social, tool, …)
- Audience and context
- Style keywords (minimal, dark, playful, …)
- Platform and stack (web, React, Next.js, React Native, Flutter, desktop, …)
- Content density, key workflow, states, and accessibility needs

### 2. Generate a design system (required for new pages / visual systems)

```bash
python3 <SKILL_DIR>/scripts/search.py "<product> <industry> <keywords>" --design-system -p "Project Name"
```

Persist across sessions:

```bash
python3 <SKILL_DIR>/scripts/search.py "<query>" --design-system --persist -p "Project Name"
python3 <SKILL_DIR>/scripts/search.py "<query>" --design-system --persist -p "Project Name" --page "dashboard"
```

Creates `design-system/MASTER.md` and optional `design-system/pages/<page>.md`. When building a page: read page override if it exists, else MASTER.

Markdown output: add `-f markdown`.

### 3. Domain search (as needed)

```bash
python3 <SKILL_DIR>/scripts/search.py "<keyword>" --domain <domain> [-n 3]
```

| Domain | Use |
|--------|-----|
| `product` | Product / industry patterns |
| `style` | UI styles and effects |
| `color` | Palettes |
| `typography` | Font pairings |
| `google-fonts` | Single Google Font lookup |
| `landing` | Page structure / CTA |
| `chart` | Chart types |
| `ux` | Best practices / anti-patterns |
| `icons` | Icon recommendations |
| `react` | List/perf patterns (apply to RN where relevant) |
| `web` | App a11y, touch, safe areas |

### 4. Stack guidelines

```bash
python3 <SKILL_DIR>/scripts/search.py "<keyword>" --stack react-native
```

Then implement **only** that system. Do not invent a competing palette, type stack, or style.

## Anti-AI look (hard rules)

Generic model UI is a defect. If the screen could pass as a default ChatGPT/Claude mock, reject and redesign.

**Forbidden unless the design system explicitly requires it**

- Purple/pink/violet mesh gradients, indigo-on-white SaaS chrome, “AI glow”
- Inter / Roboto / Arial / system as the only typeface for a branded product
- Emoji as icons; 3 identical icon+title+paragraph feature cards
- Fake 5-star rows, generic stock avatars, lorem, “Unlock the future / Seamless / Next-gen”
- Default gray cards, 8/12/16 radius everywhere, drop-shadow-on-every-card
- Glassmorphism, neon, or Bento grid as decoration with no product reason
- Centered hero + 3 columns + logo cloud + identical CTA twice as the only layout

**Required**

- Use `--design-system` colors, type pairing, effects, and **anti-patterns** verbatim
- Type: the recommended heading+body pair from `--domain typography`, not a default system font
- Color: tokens from the system (primary / surface / text / CTA). No leftover `#6366F1` / `#8B5CF6`
- One primary CTA per screen; hierarchy via size, weight, and space — not rainbow accents
- Real product language: specific nouns, numbers, constraints. No filler marketing
- Icons: use the project's existing icon library; otherwise use Phosphor with one weight and semantic names matched to the action

If `--design-system` itself looks generic, re-run with sharper product keywords (industry, audience, tone, density) until the palette and type are distinctive.

## Query tips

Combine product + industry + tone: `"beauty spa wellness calm"` not `"app"`.

| Task | Start with |
|------|------------|
| New page / app | Step 2 `--design-system` |
| New component | `--domain style` + `--domain ux` + `--stack react-native` |
| Review / a11y | `--domain ux` + `--domain web` |
| Charts | `--domain chart` |
| Dark mode | `--domain style "dark mode"` + `--domain ux` |

## Icons

Default library: **Phosphor** (`@phosphor-icons/react`). `data/icons.csv` is a shortlist, not the full set. If the shortlist has no match, pick a better Phosphor icon; Heroicons only if Phosphor has none. Never use emoji as UI icons.

## Pre-delivery

- Screenshot the UI in your head: would it look like every other AI demo? If yes, change type, color, and layout before shipping
- Anti-patterns from `--design-system` are all absent
- No emoji icons; one icon family and stroke style
- Semantic color tokens, not one-off hex per screen
- Press feedback 80–150ms; micro-interactions 150–300ms
- Touch targets ≥44pt (iOS) / ≥48dp (Android), or an equivalent accessible target on other platforms
- Safe areas, viewport insets, sticky controls, and keyboard behavior respected where applicable
- Contrast: body ≥4.5:1, secondary ≥3:1, both light and dark
- Color is not the only indicator; labels on icon-only controls
- Reduced motion, zoom/text scaling, and Dynamic Type do not break layout
- `--domain ux "animation accessibility loading"` before shipping
