import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReviewQueue } from "./ReviewQueue";

const EXACT_QUOTE = "The vehicle completed the test.";

function reviewCase(overrides: Record<string, unknown> = {}) {
  return {
    reviewId: 11,
    reason: "HIGH_IMPACT",
    priority: 1,
    flukeStatus: "COMPLETE",
    flukeResult: "MISMATCH",
    createdAt: "2026-07-13T00:00:00Z",
    eventId: 21,
    eventType: "FLIGHT_TEST",
    occurredOn: "2026-01-30",
    actor: "SpaceX",
    verificationLevel: "OFFICIAL",
    impactScore: 8.1,
    claimedLevel: 8,
    nodeCode: "P1-ORBIT-REFUEL",
    nodeName: "궤도 추진제 이송·급유",
    scaleType: "TRL",
    currentLevel: 6,
    sourceCount: 2,
    evidence: [
      {
        kind: "VERBATIM",
        sourceLabel: "Refueling milestone story",
        url: "https://spacenews.test/case",
        evidenceQuote: EXACT_QUOTE,
        factSummary: null,
        locator: null,
        accessedOn: null,
      },
    ],
    status: "PENDING",
    reviewerNote: null,
    ...overrides,
  };
}

function stubFetch(getStatus: number, cases: unknown[], postStatus = 200) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const impl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if ((init?.method ?? "GET") === "GET") {
      return {
        ok: getStatus >= 200 && getStatus < 300,
        status: getStatus,
        json: async () => cases,
      } as Response;
    }
    return {
      ok: postStatus >= 200 && postStatus < 300,
      status: postStatus,
      json: async () => ({}),
    } as Response;
  });
  vi.stubGlobal("fetch", impl);
  return { impl, calls };
}

async function renderLoadedQueue(cases: unknown[] = [reviewCase()], postStatus = 200) {
  const stub = stubFetch(200, cases, postStatus);
  render(<ReviewQueue />);
  fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
  fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));
  if (cases.length > 0) {
    await screen.findByText("P1-ORBIT-REFUEL");
  }
  return stub;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ReviewQueue", () => {
  it("renders priority, evidence, and current-to-proposed level after token submit", async () => {
    const { calls } = await renderLoadedQueue();

    expect(screen.getByText("6 → 8")).toBeInTheDocument();
    expect(screen.getByText(EXACT_QUOTE)).toBeInTheDocument();
    expect(screen.getByText("원문 인용")).toBeInTheDocument();
    expect(screen.getByText("MISMATCH")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /refueling milestone story/i });
    expect(link).toHaveAttribute("href", "https://spacenews.test/case");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
    expect(calls[0].init?.headers).toMatchObject({ "X-Tracker-Admin-Token": "secret" });
  });

  it("renders reviewed historical facts as summaries, never quotations", async () => {
    const stub = stubFetch(200, [
      reviewCase({
        evidence: [
          {
            kind: "HISTORICAL_REFERENCE",
            sourceLabel: "NASA",
            url: "https://www.nasa.gov/history/apollo-11-mission-overview/",
            evidenceQuote: null,
            factSummary: "Apollo 11 completed the first crewed lunar landing in 1969.",
            locator: "Mission overview",
            accessedOn: "2026-07-13",
          },
        ],
      }),
    ]);
    const { container } = render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));

    expect(await screen.findByText("인간 검수 사실 요약")).toBeInTheDocument();
    const summary = screen.getByText(/first crewed lunar landing/i);
    expect(summary.tagName).toBe("P");
    expect(summary.closest("blockquote")).toBeNull();
    expect(container.querySelector("blockquote")).toBeNull();
    expect(screen.getByText("Mission overview")).toBeInTheDocument();
    expect(screen.getByText("확인일 2026-07-13")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "NASA" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
    expect(stub.impl).toHaveBeenCalledTimes(1);
  });

  it("normalizes the previous verbatim evidence field names during rollout", async () => {
    await renderLoadedQueue([
      reviewCase({
        evidence: [
          {
            articleTitle: "Legacy evidence title",
            articleUrl: "https://spacenews.test/legacy",
            evidenceQuote: "Legacy verified quote.",
          },
        ],
      }),
    ]);

    expect(screen.getByText("원문 인용")).toBeInTheDocument();
    expect(screen.getByText("Legacy verified quote.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Legacy evidence title" }))
      .toHaveAttribute("href", "https://spacenews.test/legacy");
  });

  it("does not submit a rejection without a note", async () => {
    const { impl } = await renderLoadedQueue();

    fireEvent.click(screen.getByRole("button", { name: /^reject$/i }));

    expect(screen.getByText(/rejection note is required/i)).toBeInTheDocument();
    expect(impl).toHaveBeenCalledTimes(1);
  });

  it("approves after explicit confirmation and removes the case", async () => {
    const { calls } = await renderLoadedQueue();

    fireEvent.click(screen.getByRole("button", { name: /^approve$/i }));
    fireEvent.click(screen.getByRole("button", { name: /confirm/i }));

    await waitFor(() =>
        expect(screen.queryByText("P1-ORBIT-REFUEL")).not.toBeInTheDocument());
    const post = calls.find(call => call.init?.method === "POST");
    expect(post?.url).toContain("/api/tracker/admin/review/11");
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({ decision: "APPROVE" });
  });

  it("cancelling the confirmation keeps the case and sends nothing", async () => {
    const { impl } = await renderLoadedQueue();

    fireEvent.click(screen.getByRole("button", { name: /^approve$/i }));
    fireEvent.click(screen.getByRole("button", { name: /cancel/i }));

    expect(screen.getByText("P1-ORBIT-REFUEL")).toBeInTheDocument();
    expect(impl).toHaveBeenCalledTimes(1);
  });

  it("shows an unauthorized message on 401", async () => {
    stubFetch(401, []);
    render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));

    expect(await screen.findByText(/unauthorized/i)).toBeInTheDocument();
  });

  it("shows a conflict message when the review was already resolved", async () => {
    await renderLoadedQueue([reviewCase()], 409);

    fireEvent.click(screen.getByRole("button", { name: /^approve$/i }));
    fireEvent.click(screen.getByRole("button", { name: /confirm/i }));

    expect(await screen.findByText(/already resolved/i)).toBeInTheDocument();
    expect(screen.getByText("P1-ORBIT-REFUEL")).toBeInTheDocument();
  });

  it("shows the empty state for a drained queue", async () => {
    stubFetch(200, []);
    render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));

    expect(await screen.findByText(/no pending reviews/i)).toBeInTheDocument();
  });

  it("labels terminal filter failures for attention", async () => {
    await renderLoadedQueue([
      reviewCase({ flukeStatus: "FAILED", flukeResult: null, priority: 2 }),
    ]);

    expect(screen.getByText("FILTER FAILED")).toBeInTheDocument();
  });
});
