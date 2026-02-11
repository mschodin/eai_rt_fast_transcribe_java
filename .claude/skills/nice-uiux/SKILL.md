---
name: nice-uiux
description: NICE brand design system — colors, typography, spacing, components, and visual patterns. Used by implementer and qa agents to ensure UI matches nice.com brand guidelines.
user_invocable: false
---

# NICE Brand Design System

Reference: https://nice.com

## Tailwind Theme (single source of truth)

```ts
// tailwind.config.ts — extend section
{
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Be Vietnam Pro"', 'sans-serif'],
      },
      colors: {
        nice: {
          black: '#22212b',       // Dark sections, footer, primary buttons
          primary: '#3694fd',     // CTA buttons, links, accents
          'dark-blue': '#2c79ee', // Hover states
          text: '#11181c',        // Body text (not pure black)
          'gray-text': '#6d6d72', // Secondary/muted text
          'gray-bg': '#f2f0eb',   // Alternating section backgrounds (warm gray)
          'gray-base': '#e8e6e0', // Borders, dividers
          pink: '#ff5b8a',        // Alerts (sparingly)
          'dark-red': '#e73b71',  // Error/destructive (sparingly)
          green: '#00e2a0',       // Success (sparingly)
        },
      },
      borderRadius: {
        card: '32px',
        'card-lg': '40px',
        pill: '38px',
      },
      fontSize: {
        hero: ['54px', { lineHeight: '1.2', letterSpacing: '-0.03em', fontWeight: '500' }],
        section: ['42px', { lineHeight: '1.2', fontWeight: '500' }],
        subsection: ['36px', { lineHeight: '1.2', fontWeight: '500' }],
        'body-lg': ['20px', { lineHeight: '1.35', fontWeight: '300' }],
      },
    },
  },
}
```

Font import:
```
@import url('https://fonts.googleapis.com/css2?family=Be+Vietnam+Pro:wght@300;400;500;600;700&display=swap');
```

## Typography Rules

- Headings: weight **500** (medium) — never bold 700
- Body: weight **300** (light) for large text, **400** (regular) for standard
- Buttons and nav links: weight **300** at 16px

## Component Reference

| Component | Tailwind |
|-----------|----------|
| Section | `py-16 md:py-24 px-4 max-w-7xl mx-auto` |
| Dark section | `bg-nice-black text-white py-16 md:py-24` |
| Hero gradient | `bg-gradient-to-br from-purple-800 via-indigo-700 to-blue-500 text-white` |
| H1 | `text-hero text-nice-black` |
| H2 | `text-section text-nice-black` |
| Body large | `text-body-lg text-nice-gray-text` |
| Body | `text-base font-normal text-nice-text` |
| Primary button | `bg-nice-black text-white rounded-full px-6 py-3 font-light` |
| Blue button | `bg-nice-primary text-nice-black rounded-full px-5 py-2 font-light` |
| Ghost button | `border border-nice-black text-nice-black rounded-full px-6 py-3 font-light` |
| Card | `bg-white rounded-card p-8` |
| Featured card | `bg-white rounded-card-lg p-8` |
| Footer | `bg-nice-black text-white` |

## Visual Rules

1. **No shadows** — completely flat design. No `box-shadow`, no `drop-shadow`.
2. **Pill shapes** — all buttons, tags, badges use `rounded-full` or `rounded-pill`.
3. **Large card radius** — cards use `rounded-card` (32px), not typical 8-12px.
4. **Warm grays only** — never use Tailwind's default cool/blue `gray-*`. Use `nice-gray-*`.
5. **Dark text on blue buttons** — `nice-primary` pairs with `nice-black` text, never white.
6. **Alternating sections** — white and `nice-gray-bg` backgrounds alternate.
7. **Generous whitespace** — sections breathe. Use `py-16` to `py-24`, 24-32px grid gaps.
8. **Purple-blue gradient** — hero sections only. Never on cards or buttons.
