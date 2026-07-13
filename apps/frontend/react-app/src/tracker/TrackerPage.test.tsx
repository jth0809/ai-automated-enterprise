import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TrackerPage } from "./TrackerPage";

afterEach(() => {
  vi.restoreAllMocks();
});

function stubTrackerRoutes() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.includes("/api/tracker/summary")
        ? {
            displayedEtaYear: 2048.3,
            etaLow: 2042,
            etaHigh: 2056,
            label: "현 추세 지속 시나리오 기준 · 모델 내 80% 구간",
            overallReadiness: 0.12,
            bottleneckPillar: 3,
            frozen: false,
          }
        : url.includes("/api/tracker/pillars")
          ? [1, 2, 3, 4, 5, 6].map((pillar) => ({
              pillar,
              name: `pillar-${pillar}`,
              readiness: pillar / 10,
              etaYear: null,
              momentum: null,
            }))
          : url.includes("/api/tracker/events")
            ? [
                {
                  occurredOn: "2021-04-20",
                  nodeName: "ISRU: 추진제·물·산소 현지 생산",
                  eventType: "FLIGHT_TEST",
                  levelFrom: 0,
                  levelTo: 6,
                  impactScore: null,
                  verificationLevel: "OFFICIAL",
                  sourceCount: 0,
                  evidenceQuote: null,
                },
              ]
            : { error: "unexpected" };
      return { ok: true, status: 200, json: async () => body } as Response;
    }),
  );
}

describe("TrackerPage", () => {
  it("renders countdown, radar, and timeline from the three public endpoints", async () => {
    stubTrackerRoutes();
    const { container } = render(<TrackerPage />);

    expect(await screen.findByText("2048")).toBeInTheDocument();
    expect(
      screen.getByText("현 추세 지속 시나리오 기준 · 모델 내 80% 구간"),
    ).toBeInTheDocument();
    expect(container.querySelector("polygon.radar-value")).not.toBeNull();
    expect(
      await screen.findByText(/ISRU: 추진제·물·산소 현지 생산/),
    ).toBeInTheDocument();
    expect(screen.getByText("FLIGHT_TEST")).toBeInTheDocument();
    // The admin review queue is collapsed below the public dashboard and
    // fetches nothing until a token is submitted.
    expect(screen.getByText(/검수 큐/)).toBeInTheDocument();
  });
});
