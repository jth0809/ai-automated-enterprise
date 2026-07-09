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
