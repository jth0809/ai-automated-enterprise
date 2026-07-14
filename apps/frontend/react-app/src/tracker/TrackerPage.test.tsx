import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TrackerPage } from "./TrackerPage";
import { EventTimeline } from "./EventTimeline";

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
                  occurredOnPrecision: "DAY",
                  nodeName: "ISRU: 추진제·물·산소 현지 생산",
                  eventType: "FLIGHT_TEST",
                  levelFrom: 0,
                  levelTo: 6,
                  impactScore: null,
                  verificationLevel: "OFFICIAL",
                  sourceCount: 0,
                  evidenceQuote: null,
                  primaryEvidence: null,
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

describe("EventTimeline evidence", () => {
  it("renders reviewed historical facts without quotation styling", () => {
    const { container } = render(
      <EventTimeline
        events={[
          {
            occurredOn: "1969-01-01",
            occurredOnPrecision: "YEAR",
            nodeName: "Crewed lunar landing",
            eventType: "MILESTONE",
            levelFrom: 5,
            levelTo: 6,
            impactScore: null,
            verificationLevel: "OFFICIAL",
            sourceCount: 1,
            evidenceQuote: null,
            primaryEvidence: {
              kind: "HISTORICAL_REFERENCE",
              sourceLabel: "NASA",
              url: "https://www.nasa.gov/history/apollo-11-mission-overview/",
              evidenceQuote: null,
              factSummary: "Apollo 11 completed the first crewed lunar landing in 1969.",
              locator: "Mission overview",
              accessedOn: "2026-07-13",
            },
          },
        ]}
      />,
    );

    expect(screen.getByText("1969")).toBeInTheDocument();
    expect(screen.getByText("인간 검수 사실 요약")).toBeInTheDocument();
    const summary = screen.getByText(/first crewed lunar landing/i);
    expect(summary.tagName).toBe("P");
    expect(summary.closest("blockquote")).toBeNull();
    expect(container.querySelector("blockquote")).toBeNull();
    expect(screen.getByText("Mission overview")).toBeInTheDocument();
    expect(screen.getByText("확인일 2026-07-13")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "NASA" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
  });

  it("keeps verbatim evidence in quotation styling", () => {
    const { container } = render(
      <EventTimeline
        events={[
          {
            occurredOn: "2026-01-30",
            occurredOnPrecision: "DAY",
            nodeName: "Orbital refueling",
            eventType: "FLIGHT_TEST",
            levelFrom: 5,
            levelTo: 6,
            impactScore: 7.2,
            verificationLevel: "OFFICIAL",
            sourceCount: 1,
            evidenceQuote: "The vehicle completed the test.",
            primaryEvidence: {
              kind: "VERBATIM",
              sourceLabel: "NASA",
              url: "https://www.nasa.gov/test",
              evidenceQuote: "The vehicle completed the test.",
              factSummary: null,
              locator: null,
              accessedOn: null,
            },
          },
        ]}
      />,
    );

    expect(screen.getByText("원문 인용")).toBeInTheDocument();
    expect(container.querySelector("blockquote")).toHaveTextContent(
      "The vehicle completed the test.",
    );
  });
});
