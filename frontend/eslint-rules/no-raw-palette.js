/**
 * Disallows raw Tailwind palette classes (e.g. `bg-indigo-500`) and arbitrary
 * pixel font sizes (e.g. `text-[10px]`) outside `src/ui/`. Feature code must use
 * design tokens instead, so the palette is defined in one place.
 */

const PALETTE_CLASS =
  /\b(bg|text|border|ring|fill|stroke|from|to|via)-(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b/g;
const ARBITRARY_SIZE = /text-\[\d+px\]/g;
const UI_FILE = /(^|\/)src\/ui\//;

/** @type {import("eslint").Rule.RuleModule} */
const rule = {
  meta: {
    type: "problem",
    docs: {
      description: "Disallow raw palette classes outside src/ui/; use design tokens instead",
    },
    schema: [],
    messages: {
      rawPalette:
        "Raw class '{{match}}' is not allowed outside src/ui/. Use a design token (e.g. bg-accent) instead.",
    },
  },
  create(context) {
    const filename = context.filename.replace(/\\/g, "/");
    if (UI_FILE.test(filename)) {
      return {};
    }

    function report(node, text) {
      for (const pattern of [PALETTE_CLASS, ARBITRARY_SIZE]) {
        for (const match of text.matchAll(pattern)) {
          context.report({ node, messageId: "rawPalette", data: { match: match[0] } });
        }
      }
    }

    return {
      Literal(node) {
        if (typeof node.value === "string") {
          report(node, node.value);
        }
      },
      TemplateElement(node) {
        if (node.value.cooked != null) {
          report(node, node.value.cooked);
        }
      },
    };
  },
};

export default rule;
