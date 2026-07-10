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

/**
 * True when two fragments read as the same text once case, punctuation, and
 * whitespace are folded away. Google News feeds put the headline in both the
 * item title ("Headline - Source") and the description ("Headline&nbsp;Source"
 * as a link) — this is how the feed detects that the excerpt adds nothing.
 */
export function isSameText(a: string, b: string): boolean {
  const na = normalize(a);
  return na !== "" && na === normalize(b);
}

function normalize(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
}
