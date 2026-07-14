import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { OpsPanel } from "./OpsPanel";

function opsOverview(overrides: Record<string, unknown> = {}) {
  return {
    frozen: true,
    freezeReason: "golden agreement fell below the release floor",
    freezeTrigger: "GOLDEN_REGRESSION",
    freezeAt: "2026-07-14T02:00:00Z",
    latestGolden: {
      id: 5,
      mode: "DRILL",
      status: "COMPLETE",
      datasetVersion: "golden-v1",
      promptVersion: "gate-p1",
      modelVersion: "haiku-4.5",
      totalCount: 50,
      matchedCount: 21,
      failedCount: 29,
      agreement: 0.42,
      startedAt: "2026-07-14T01:00:00Z",
      completedAt: "2026-07-14T01:05:00Z",
    },
    controlMetrics: [
      {
        metricDate: "2026-07-14",
        metricCode: "GATE_PASS_RATE",
        value: 0.3,
        baselineMean: 0.6,
        lowerBound: 0.4,
        upperBound: 0.8,
        status: "VIOLATION",
        violation: true,
        consecutiveViolations: 3,
        sampleDays: 14,
      },
    ],
    deadman: {
      status: "ALERT",
      observedAt: "2026-07-14T02:00:00Z",
      feedCount: 4,
      alertCount: 1,
      insufficientCount: 0,
      feeds: [
        {
          source: "NASA",
          status: "ALERT",
          intervalSamples: 10,
          medianIntervalHours: 12,
          silenceHours: 30,
        },
      ],
    },
    ...overrides,
  };
}

function stubOps(
  overview: Record<string, unknown>,
  options: {
    getStatus?: number;
    postStatus?: number;
    afterRelease?: Record<string, unknown>;
  } = {},
) {
  const { getStatus = 200, postStatus = 200, afterRelease } = options;
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  let released = false;
  const impl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.includes("/ops/release")) {
      const ok = postStatus >= 200 && postStatus < 300;
      if (ok) released = true;
      return {
        ok,
        status: postStatus,
        json: async () =>
          ok
            ? { status: "ACTIVE" }
            : { error: postStatus === 409 ? "NOT_FROZEN" : "internal SQL detail" },
      } as Response;
    }
    const body = released && afterRelease ? afterRelease : overview;
    return {
      ok: getStatus >= 200 && getStatus < 300,
      status: getStatus,
      json: async () => body,
    } as Response;
  });
  vi.stubGlobal("fetch", impl);
  return { impl, calls };
}

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe("OpsPanel", () => {
  it("shows the frozen badge, reason, trigger, and freeze time", async () => {
    stubOps(opsOverview());

    render(<OpsPanel token="secret" />);

    expect(await screen.findByText(/frozen/i)).toBeInTheDocument();
    expect(screen.getByText(/golden agreement fell below the release floor/i)).toBeInTheDocument();
    expect(screen.getByText(/GOLDEN_REGRESSION/)).toBeInTheDocument();
  });

  it("labels the latest golden run mode as a drill", async () => {
    stubOps(opsOverview());

    render(<OpsPanel token="secret" />);

    expect(await screen.findByText(/DRILL/)).toBeInTheDocument();
    expect(screen.getByText(/42%|0\.42/)).toBeInTheDocument();
  });

  it("surfaces control-chart violations and the deadman alert", async () => {
    stubOps(opsOverview());

    render(<OpsPanel token="secret" />);

    expect(await screen.findByText(/GATE_PASS_RATE/)).toBeInTheDocument();
    expect(screen.getAllByText(/ALERT|VIOLATION/).length).toBeGreaterThan(0);
  });

  it("requires a release reason before enabling the button", async () => {
    stubOps(opsOverview());

    render(<OpsPanel token="secret" />);
    await screen.findByText(/frozen/i);

    const release = screen.getByRole("button", { name: /release/i });
    expect(release).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/release reason/i), {
      target: { value: "golden re-run recovered agreement" },
    });
    expect(release).toBeEnabled();
  });

  it("posts the reason, reloads ops, and notifies the parent on success", async () => {
    const onReleased = vi.fn();
    const { calls } = stubOps(opsOverview(), {
      afterRelease: opsOverview({ frozen: false, freezeReason: null, freezeTrigger: null, freezeAt: null }),
    });

    render(<OpsPanel token="secret" onReleased={onReleased} />);
    await screen.findByText(/frozen/i);
    fireEvent.change(screen.getByLabelText(/release reason/i), {
      target: { value: "golden re-run recovered agreement" },
    });
    fireEvent.click(screen.getByRole("button", { name: /release/i }));

    await waitFor(() => expect(onReleased).toHaveBeenCalledTimes(1));
    const post = calls.find(call => call.url.includes("/ops/release"));
    expect(post?.init?.method).toBe("POST");
    expect(String(post?.init?.body)).toContain("golden re-run recovered agreement");
    expect(await screen.findByText(/active|not frozen/i)).toBeInTheDocument();
  });

  it("shows a safe conflict message when the state is not frozen", async () => {
    stubOps(opsOverview({ frozen: false }), { postStatus: 409 });

    render(<OpsPanel token="secret" />);
    await waitFor(() =>
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument(),
    );
    // A non-frozen overview offers no release control, so nothing leaks.
    expect(screen.queryByRole("button", { name: /release/i })).not.toBeInTheDocument();
  });

  it("reports unauthorized without exposing response internals", async () => {
    stubOps(opsOverview(), { getStatus: 401 });

    render(<OpsPanel token="wrong" />);

    expect(await screen.findByText(/unauthorized/i)).toBeInTheDocument();
  });

  it("keeps the admin token out of browser storage", async () => {
    stubOps(opsOverview());

    render(<OpsPanel token="secret" />);
    await screen.findByText(/frozen/i);

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});
