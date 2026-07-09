/**
 * Extracts readable plain text from an HTML fragment. RSS excerpts often
 * carry markup (<img>, <b>, <a>, entities) that must not render as literal
 * text. DOMParser parses without executing scripts; script/style bodies are
 * removed so their contents never surface as text, and whitespace left
 * behind by removed tags is collapsed.
 */
export function stripHtml(html: string): string {
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.body.querySelectorAll("script, style").forEach((el) => el.remove());
  return (doc.body.textContent ?? "").replace(/\s+/g, " ").trim();
}
