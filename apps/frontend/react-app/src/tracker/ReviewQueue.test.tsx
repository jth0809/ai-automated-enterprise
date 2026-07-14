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
    resolvedAt: null,
    ...overrides,
  };
}

function stubFetch(
  getStatus: number,
  cases: unknown[],
  postStatus = 200,
  paging: { total: number; totalPages: number } | null = null,
) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  let decided = false;
  const impl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if ((init?.method ?? "GET") === "GET") {
      const visibleCases = decided ? [] : cases;
      const requestedPage = Number(new URL(url, "http://localhost").searchParams.get("page") ?? 0);
      return {
        ok: getStatus >= 200 && getStatus < 300,
        status: getStatus,
        json: async () => ({
          items: visibleCases,
          page: requestedPage,
          size: 25,
          total: decided ? 0 : (paging?.total ?? visibleCases.length),
          totalPages: decided ? 0 : (paging?.totalPages ?? (visibleCases.length === 0 ? 0 : 1)),
          sort: "priority DESC, created_at ASC, id ASC",
        }),
      } as Response;
    }
    if (postStatus >= 200 && postStatus < 300) decided = true;
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
  it("does not request admin data before an in-memory token is submitted", () => {
    const { impl } = stubFetch(200, [reviewCase()]);

    render(<ReviewQueue />);

    expect(impl).not.toHaveBeenCalled();
  });

  it("shows loading and a safe error without exposing response details", async () => {
    let finish: ((response: Response) => void) | undefined;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { finish = resolve; })));
    render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));

    expect(screen.getByText(/loading/i)).toBeInTheDocument();
    finish?.({ ok: false, status: 500, json: async () => ({ detail: "internal SQL" }) } as Response);

    expect(await screen.findByText(/failed to load the review queue/i)).toBeInTheDocument();
    expect(screen.queryByText(/internal SQL/i)).not.toBeInTheDocument();
  });

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

  it("switches pending, approved, and rejected history through allowlisted queries", async () => {
    const { calls } = await renderLoadedQueue();

    fireEvent.click(screen.getByRole("button", { name: /^approved$/i }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1].url).toContain("status=APPROVED");
    expect(calls[1].url).toContain("page=0");

    fireEvent.click(screen.getByRole("button", { name: /^rejected$/i }));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2].url).toContain("status=REJECTED");
  });

  it("applies a reason filter and requests the next bounded page", async () => {
    const stub = stubFetch(200, [reviewCase()], 200, { total: 30, totalPages: 2 });
    render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));
    await screen.findByText("P1-ORBIT-REFUEL");

    fireEvent.change(screen.getByLabelText(/reason filter/i), {
      target: { value: "LEVEL_JUMP" },
    });
    await waitFor(() => expect(stub.calls).toHaveLength(2));
    expect(stub.calls[1].url).toContain("reason=LEVEL_JUMP");
    expect(screen.getByText(/30 total/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /next page/i }));
    await waitFor(() => expect(stub.calls).toHaveLength(3));
    expect(stub.calls[2].url).toContain("page=1");
    expect(stub.calls[2].url).toContain("size=25");
  });

  it("moves back one page when a decision drains the last page", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    let decided = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, init });
      if ((init?.method ?? "GET") === "POST") {
        decided = true;
        return { ok: true, status: 200, json: async () => ({}) } as Response;
      }
      const requestedPage = Number(
        new URL(url, "http://localhost").searchParams.get("page") ?? 0,
      );
      const items = requestedPage === 1
        ? (decided ? [] : [reviewCase({ reviewId: 12, nodeCode: "P2-LIFE-SUPPORT" })])
        : [reviewCase()];
      return {
        ok: true,
        status: 200,
        json: async () => ({
          items,
          page: requestedPage,
          size: 25,
          total: decided ? 25 : 26,
          totalPages: decided ? 1 : 2,
          sort: "priority DESC, created_at ASC, id ASC",
        }),
      } as Response;
    }));

    render(<ReviewQueue />);
    fireEvent.change(screen.getByLabelText(/admin token/i), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: /load reviews/i }));
    await screen.findByText("P1-ORBIT-REFUEL");
    fireEvent.click(screen.getByRole("button", { name: /next page/i }));
    await screen.findByText("P2-LIFE-SUPPORT");

    fireEvent.click(screen.getByRole("button", { name: /^approve$/i }));
    fireEvent.click(screen.getByRole("button", { name: /confirm/i }));

    await screen.findByText("P1-ORBIT-REFUEL");
    const getUrls = calls
      .filter(call => (call.init?.method ?? "GET") === "GET")
      .map(call => call.url);
    expect(getUrls.at(-2)).toContain("page=1");
    expect(getUrls.at(-1)).toContain("page=0");
  });

  it("shows resolved history without decision controls", async () => {
    await renderLoadedQueue([
      reviewCase({
        status: "APPROVED",
        reviewerNote: "two reviewers agreed",
        resolvedAt: "2026-07-14T02:00:00Z",
      }),
    ]);

    expect(screen.getByText("APPROVED")).toBeInTheDocument();
    expect(screen.getByText("two reviewers agreed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^approve$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^reject$/i })).not.toBeInTheDocument();
  });

  it("keeps the token out of storage and request URLs", async () => {
    const storageWrite = vi.spyOn(Storage.prototype, "setItem");
    const { calls } = await renderLoadedQueue();

    expect(storageWrite).not.toHaveBeenCalled();
    expect(calls.every(call => !call.url.includes("secret"))).toBe(true);
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
    expect(post?.url).toContain("/api/tracker/admin/reviews/11/decision");
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({ decision: "APPROVE" });
    expect(calls.filter(call => (call.init?.method ?? "GET") === "GET")).toHaveLength(2);
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
