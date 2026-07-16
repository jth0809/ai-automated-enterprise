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

describe("forecast comparison responsive containment", () => {
  it("lets each parent in the scroll-container chain shrink to the viewport", () => {
    for (const selector of [".content", ".tracker", ".forecast-comparison"]) {
      expect(declarations(selector)).toMatch(/min-width:\s*0\s*;/);
    }

    expect(declarations(".forecast-table-wrap")).toMatch(/overflow-x:\s*auto\s*;/);
    expect(declarations(".forecast-table-wrap")).toMatch(/min-width:\s*0\s*;/);
  });
});
