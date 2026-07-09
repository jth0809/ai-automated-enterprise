import { describe, expect, it } from "vitest";
import { stripHtml } from "./stripHtml";

describe("stripHtml", () => {
  it("returns plain text unchanged", () => {
    expect(stripHtml("Just a plain sentence.")).toBe("Just a plain sentence.");
  });

  it("strips inline formatting tags but keeps their text", () => {
    expect(stripHtml("<b>Breaking:</b> model <i>beats</i> benchmark")).toBe(
      "Breaking: model beats benchmark",
    );
  });

  it("removes img tags entirely and normalizes leftover whitespace", () => {
    expect(
      stripHtml('Intro <img src="https://cdn.example/pic.jpg" alt=""> outro'),
    ).toBe("Intro outro");
  });

  it("keeps link text but drops the anchor markup", () => {
    expect(stripHtml('Read <a href="https://news.example/a">the story</a>')).toBe(
      "Read the story",
    );
  });

  it("decodes HTML entities", () => {
    expect(stripHtml("AI &amp; ML &lt;news&gt;")).toBe("AI & ML <news>");
  });

  it("drops script and style content instead of rendering it as text", () => {
    expect(stripHtml('Hello<script>alert("x")</script> world')).toBe(
      "Hello world",
    );
    expect(stripHtml("<style>.a{color:red}</style>Styled text")).toBe(
      "Styled text",
    );
  });

  it("returns an empty string when the markup has no text", () => {
    expect(stripHtml('<img src="only-an-image.jpg">')).toBe("");
  });
});
