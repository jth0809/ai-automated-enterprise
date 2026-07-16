import { describe, expect, it } from "vitest";

const cssModules = import.meta.glob("../App.css", {
  eager: true,
  import: "default",
  query: "?raw",
});
const appCss = cssModules["../App.css"] as string;

function declarations(selector: string): string {
  const start = appCss.indexOf(`${selector} {`);
  expect(start, `missing CSS rule for ${selector}`).toBeGreaterThanOrEqual(0);
  const end = appCss.indexOf("}", start);
  expect(end, `unterminated CSS rule for ${selector}`).toBeGreaterThan(start);
  return appCss.slice(start, end);
}

describe("methodology credibility responsive containment", () => {
  it("contains every audit table in its own horizontal scroll region", () => {
    for (const selector of [".content", ".tracker", ".methodology-credibility"]) {
      expect(declarations(selector)).toMatch(/min-width:\s*0\s*;/);
    }
    expect(declarations(".credibility-table-wrap")).toMatch(
      /overflow-x:\s*auto\s*;/,
    );
    expect(declarations(".credibility-table-wrap")).toMatch(/max-width:\s*100%\s*;/);
  });

  it("wraps long Layer B indicator names before they widen the mobile document", () => {
    expect(declarations(".layer-b-name")).toMatch(/overflow-wrap:\s*anywhere\s*;/);
  });
});
