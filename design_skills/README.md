# UI/UX Pro Max

Portable UI/UX design skill for coding agents. It is agent-agnostic: use it
with Codex in VS Code, GitHub Copilot, Claude Code, Cursor, or another agent
that supports `SKILL.md` files. This directory is the single skill root: it
contains `README.md`, `SKILL.md`, `data/`, and `scripts/`.

## Requirements

- Python 3.10 or newer
- No npm package, marketplace extension, or network access is required

Verify Python on Windows:

```powershell
python --version
```

## Installation

Keep the complete directory intact so the agent can find `SKILL.md`,
`scripts/`, and `data/` together. Place it in the skill directory supported by
the host agent. Common repository-local locations are:

- `.github/skills/ui-ux-pro-max/`
- `.codex/skills/ui-ux-pro-max/`

For a user-level Codex setup, use `$HOME/.codex/skills/ui-ux-pro-max/`.
Other agents have equivalent skill directories. The skill itself does not
assume Cursor or any other vendor. If the host agent already exposes this
repository as a skill, no copy is needed.

## Use

Ask naturally, for example:

- Build a landing page for a compliance SaaS
- Review this screen for accessibility and visual hierarchy
- Recommend colors and type for a Vietnamese wellness app
- Refactor this dashboard so it does not look AI-generated

For a new page or visual system, generate a design system first:

```powershell
python <SKILL_DIR>\scripts\search.py "compliance SaaS operations dashboard" --design-system -p "Product Name" -f markdown
```

Use focused searches for decisions:

```powershell
python <SKILL_DIR>\scripts\search.py "keyboard navigation focus loading" --domain ux
python <SKILL_DIR>\scripts\search.py "dashboard data table" --domain chart
python <SKILL_DIR>\scripts\search.py "forms keyboard safe area" --stack react-native
```

`<SKILL_DIR>` is the directory containing `SKILL.md`; use an absolute path if
the agent's working directory is elsewhere. Available domains are `product`,
`style`, `color`, `chart`, `landing`, `ux`, `typography`, `google-fonts`,
`icons`, `react`, and `web`. The only bundled stack dataset is currently
`react-native`; do not invent stack guidance when no dataset exists.

Persist a project-specific source of truth with:

```powershell
python <SKILL_DIR>\scripts\search.py "editorial analytics workspace" --design-system --persist -p "Project Name" --page dashboard
```

This writes `design-system/<project>/MASTER.md` and an optional page override.
Read the page override first when building that page.

## Agent contract

1. Read `SKILL.md` before making visual decisions.
2. Extract product, audience, platform, density, content, workflow, and
	accessibility requirements instead of assuming generic SaaS defaults.
3. Run `--design-system` for a new visual system and use its tokens, type
	pairing, style, layout, effects, and anti-patterns.
4. Use focused `--domain` and `--stack` searches when a decision needs more
	evidence.
5. Respect the project's existing framework and icon library. Use the skill's
	recommended icon family only when no project choice exists.
6. Validate responsive behavior, accessibility, touch targets on mobile,
	contrast, reduced motion, and loading, empty, error, and success states.

The quality bar is specific product language, deliberate hierarchy, real
content, restrained motion, and a visual identity earned from the product.
Reject generic purple AI chrome, filler copy, decorative card grids, and
default typography when the system does not call for them.
