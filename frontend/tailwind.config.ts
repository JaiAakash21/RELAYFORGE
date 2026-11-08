import type { Config } from "tailwindcss";

/**
 * RelayForge design tokens.
 *
 * Every colour is declared here as a semantic name — `surface-2`, `border-subtle`,
 * `status-retry` — rather than used as a raw hex in a component. That has two
 * practical consequences: a component never needs to know which grey it is using,
 * and changing the palette is a change to this file rather than a search across
 * the codebase.
 *
 * The scale is deliberately short. A dozen greys invites inconsistency, because
 * nobody can remember which of `#1a1a1f` and `#1c1c21` is the card background.
 * Four surface levels, three border weights and three text weights cover every
 * screen in the product.
 */
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // ---- Surfaces: increasing elevation, cool-neutral rather than pure grey.
        // A faint blue cast reads as "instrument panel" instead of "washed out",
        // and keeps large dark areas from looking muddy on cheap displays.
        canvas: "#08080b",
        surface: {
          DEFAULT: "#0d0d11",
          2: "#131318",
          3: "#1a1a21",
          4: "#22222b",
        },

        // ---- Borders: hairlines. Separation here comes from a 1px border, not
        // from a drop shadow — shadows on a dark background read as smudges.
        border: {
          DEFAULT: "#23232c",
          subtle: "#1a1a21",
          strong: "#32323e",
        },

        // ---- Text: three weights only. Anything needing a fourth is a sign the
        // hierarchy is being expressed with colour where it should use size,
        // weight or position.
        content: {
          DEFAULT: "#ededf0",
          secondary: "#9c9cab",
          tertiary: "#6c6c7c",
          inverse: "#08080b",
        },

        // ---- Accent: one restrained indigo. Used for primary actions, the
        // active navigation item and focus rings — nothing else. An accent that
        // appears everywhere stops meaning anything.
        accent: {
          DEFAULT: "#6366f1",
          hover: "#7c7ff5",
          muted: "#312e81",
          subtle: "#1e1b4b",
          fg: "#ffffff",
        },

        // ---- Status: mapped to delivery states, not to generic severities, so a
        // component can say `status-retry` and be unambiguous.
        status: {
          success: "#2dd47f",
          "success-bg": "#0b2e1c",
          "success-border": "#15532f",

          retry: "#f0a126",
          "retry-bg": "#2e2109",
          "retry-border": "#5c4211",

          failed: "#f2556f",
          "failed-bg": "#30101a",
          "failed-border": "#5e1f2e",

          pending: "#49b6f5",
          "pending-bg": "#0a2436",
          "pending-border": "#14456a",

          progress: "#a78bfa",
          "progress-bg": "#1f1636",
          "progress-border": "#3b2a68",

          neutral: "#8b8b9a",
          "neutral-bg": "#17171d",
          "neutral-border": "#2a2a34",
        },
      },

      fontFamily: {
        sans: ["var(--font-sans)", "system-ui", "sans-serif"],
        mono: ["var(--font-mono)", "ui-monospace", "monospace"],
      },

      fontSize: {
        // A tight type scale. Dense data tables need 12-13px to stay readable at
        // high row counts, and headings need to step clearly above body text
        // without shouting.
        "2xs": ["0.6875rem", { lineHeight: "1rem", letterSpacing: "0.01em" }],
        xs: ["0.75rem", { lineHeight: "1.125rem" }],
        sm: ["0.8125rem", { lineHeight: "1.25rem" }],
        base: ["0.875rem", { lineHeight: "1.375rem" }],
        md: ["0.9375rem", { lineHeight: "1.5rem" }],
        lg: ["1.0625rem", { lineHeight: "1.625rem" }],
        xl: ["1.3125rem", { lineHeight: "1.875rem", letterSpacing: "-0.01em" }],
        "2xl": ["1.625rem", { lineHeight: "2.125rem", letterSpacing: "-0.02em" }],
        "3xl": ["2.125rem", { lineHeight: "2.5rem", letterSpacing: "-0.025em" }],
        "4xl": ["2.75rem", { lineHeight: "3rem", letterSpacing: "-0.03em" }],
        "5xl": ["3.5rem", { lineHeight: "3.75rem", letterSpacing: "-0.035em" }],
      },

      borderRadius: {
        sm: "0.3125rem",
        DEFAULT: "0.4375rem",
        md: "0.5rem",
        lg: "0.625rem",
        xl: "0.875rem",
        "2xl": "1.125rem",
      },

      boxShadow: {
        // Restrained. On a dark canvas, elevation is communicated mainly by the
        // surface step and the border; the shadow only grounds overlays.
        subtle: "0 1px 2px 0 rgb(0 0 0 / 0.3)",
        card: "0 1px 3px 0 rgb(0 0 0 / 0.4)",
        overlay: "0 16px 48px -12px rgb(0 0 0 / 0.7), 0 0 0 1px rgb(35 35 44 / 1)",
        focus: "0 0 0 2px #08080b, 0 0 0 4px #6366f1",
      },

      keyframes: {
        "fade-in": {
          from: { opacity: "0" },
          to: { opacity: "1" },
        },
        "slide-up": {
          from: { opacity: "0", transform: "translateY(4px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        "slide-in-right": {
          from: { opacity: "0", transform: "translateX(12px)" },
          to: { opacity: "1", transform: "translateX(0)" },
        },
        // A single slow pulse for live-polling indicators. Fast or large motion
        // next to numbers that are themselves changing is hard to read.
        "pulse-dot": {
          "0%, 100%": { opacity: "1", transform: "scale(1)" },
          "50%": { opacity: "0.45", transform: "scale(0.88)" },
        },
        shimmer: {
          "100%": { transform: "translateX(100%)" },
        },
      },
      animation: {
        "fade-in": "fade-in 160ms ease-out",
        "slide-up": "slide-up 180ms cubic-bezier(0.16, 1, 0.3, 1)",
        "slide-in-right": "slide-in-right 200ms cubic-bezier(0.16, 1, 0.3, 1)",
        "pulse-dot": "pulse-dot 1.8s ease-in-out infinite",
        shimmer: "shimmer 1.6s infinite",
      },
    },
  },
  plugins: [],
};

export default config;
