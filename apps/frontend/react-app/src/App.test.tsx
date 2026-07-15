import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";

afterEach(() => {
  vi.restoreAllMocks();
});

function stubAllRoutes() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.includes("/api/news")
        ? { count: 0, articles: [] }
        : url.includes("/api/status")
          ? {
              service: "backend",
              status: "UP",
              database: "UP",
              timestamp: "2026-07-09T00:00:00Z",
            }
          : url.includes("/api/tracker/summary")
            ? {
                displayedEtaYear: null,
                etaLow: null,
                etaHigh: null,
                label: "현 추세 지속 시나리오 기준 · 모델 내 80% 구간",
                overallReadiness: null,
                bottleneckPillar: null,
                frozen: false,
              }
            : url.includes("/api/tracker/pillars")
                || url.includes("/api/tracker/events")
                || url.includes("/api/tracker/layer-b")
              ? []
              : url.includes("/api/tracker/transport-economics")
                ? {
                    status: "INSUFFICIENT_DATA",
                    sufficiencyTier: "INSUFFICIENT_DATA",
                    qualificationFlags: [],
                    observationCount: 0,
                    centralTargetUsdPerKg: 200,
                    easyTargetUsdPerKg: 500,
                    hardTargetUsdPerKg: 100,
                    centralEtaYear: null,
                    earliestEtaYear: null,
                    latestEtaYear: null,
                    centralBeyondHorizon: false,
                    earliestBeyondHorizon: false,
                    latestBeyondHorizon: false,
                    priceBasisYear: 2025,
                    basis: "PUBLISHED_PRICE",
                    priceMeaning:
                      "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
                    projectionLabel:
                      "Declared-assumption scenario; not provider internal cost",
                    intervalKind: "ASSUMPTION_SENSITIVITY",
                    coherenceState: "INSUFFICIENT_DATA",
                    coherenceAlertActive: false,
                  }
                : url.includes("/api/tracker/k-index")
                  ? {
                      status: "INSUFFICIENT_DATA",
                      latestYear: null,
                      primaryEnergyTwh: null,
                      powerWatts: null,
                      kValue: null,
                      annualDelta: null,
                      typeOneGap: null,
                      typeOneMultiplier: null,
                      accountingBasis: null,
                      sourceName: null,
                      sourceUrl: null,
                      accessedOn: null,
                      series: [],
                    }
              : { error: "invalid" };
      return { ok: true, status: 200, json: async () => body } as Response;
    }),
  );
}

describe("App", () => {
  it("shows the gated résumé by default", () => {
    stubAllRoutes();
    render(<App />);
    expect(screen.getByLabelText(/access code/i)).toBeInTheDocument();
  });

  it("switches to the news feed tab", async () => {
    stubAllRoutes();
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /^news$/i }));
    expect(await screen.findByText(/no articles yet/i)).toBeInTheDocument();
  });

  it("switches to the tracker dashboard tab", async () => {
    stubAllRoutes();
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /^tracker$/i }));
    expect(await screen.findByText("2175+")).toBeInTheDocument();
  });

  it("switches to the platform status tab", async () => {
    stubAllRoutes();
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /^status$/i }));
    expect(await screen.findByText(/database/i)).toBeInTheDocument();
  });
});
