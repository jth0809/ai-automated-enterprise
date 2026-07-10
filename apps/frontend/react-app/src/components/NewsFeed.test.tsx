import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NewsFeed } from "./NewsFeed";

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("NewsFeed", () => {
  it("renders articles with title links, source, and summary", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(200, {
        count: 2,
        articles: [
          {
            title: "Model beats benchmark",
            link: "https://news.example/a",
            source: "Example Wire",
            publishedAt: "2026-07-08T09:00:00Z",
            excerpt: "Raw excerpt",
            summary: "An AI-written digest.",
          },
          {
            title: "Second story",
            link: "https://news.example/b",
            source: "Other Desk",
            publishedAt: null,
            excerpt: "Only an excerpt here",
            summary: null,
          },
        ],
      }),
    );
    render(<NewsFeed />);

    const first = await screen.findByRole("link", {
      name: /model beats benchmark/i,
    });
    expect(first).toHaveAttribute("href", "https://news.example/a");
    expect(screen.getByText("Example Wire")).toBeInTheDocument();
    expect(screen.getByText("An AI-written digest.")).toBeInTheDocument();
    // Falls back to the excerpt when there is no AI summary yet.
    expect(screen.getByText("Only an excerpt here")).toBeInTheDocument();
  });

  it("renders excerpts containing HTML as plain text, never raw markup", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(200, {
        count: 1,
        articles: [
          {
            title: "Messy feed item",
            link: "https://news.example/messy",
            source: "RSS Desk",
            publishedAt: null,
            excerpt:
              '<img src="https://cdn.example/thumb.jpg"><b>Anthropic</b> ships &amp; iterates',
            summary: null,
          },
        ],
      }),
    );
    render(<NewsFeed />);

    await screen.findByRole("link", { name: /messy feed item/i });
    // Tags are stripped, entities decoded — only readable text remains.
    expect(screen.getByText("Anthropic ships & iterates")).toBeInTheDocument();
    // The raw markup must not leak into the page as literal text.
    expect(screen.queryByText(/<b>|<img|&amp;/)).not.toBeInTheDocument();
  });

  it("hides the body when the excerpt merely restates the title (Google News)", async () => {
    // Google News RSS descriptions carry no article text — only the linked
    // headline plus the outlet name. Rendering that under the title shows
    // the same sentence twice.
    vi.stubGlobal(
      "fetch",
      mockFetch(200, {
        count: 1,
        articles: [
          {
            title: "Execs Horrified by Huge AI Bills - Yahoo Finance",
            link: "https://news.google.com/rss/articles/xyz",
            source: "news.google.com",
            publishedAt: "2026-07-08T13:57:18Z",
            excerpt:
              '<a href="https://news.google.com/rss/articles/xyz" target="_blank">Execs Horrified by Huge AI Bills</a>&nbsp;&nbsp;<font color="#6f6f6f">Yahoo Finance</font>',
            summary: null,
          },
        ],
      }),
    );
    const { container } = render(<NewsFeed />);

    await screen.findByRole("link", { name: /execs horrified/i });
    // The title stays; the redundant body paragraph must not render.
    expect(container.querySelector(".news-body")).not.toBeInTheDocument();
  });

  it("still shows an excerpt that carries real content beyond the title", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(200, {
        count: 1,
        articles: [
          {
            title: "Model beats benchmark",
            link: "https://news.example/a",
            source: "Example Wire",
            publishedAt: null,
            excerpt: "A genuinely informative first paragraph of the story.",
            summary: null,
          },
        ],
      }),
    );
    render(<NewsFeed />);

    await screen.findByRole("link", { name: /model beats benchmark/i });
    expect(
      screen.getByText("A genuinely informative first paragraph of the story."),
    ).toBeInTheDocument();
  });

  it("shows an empty state when the feed has no articles", async () => {
    vi.stubGlobal("fetch", mockFetch(200, { count: 0, articles: [] }));
    render(<NewsFeed />);
    expect(await screen.findByText(/no articles yet/i)).toBeInTheDocument();
  });

  it("shows an error state when the feed request fails", async () => {
    vi.stubGlobal("fetch", mockFetch(500, { error: "boom" }));
    render(<NewsFeed />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/feed/i);
  });
});
