import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LayerBPanel } from "./LayerBPanel";
import type { LayerBMetric } from "./api";

function metric(overrides: Partial<LayerBMetric> = {}): LayerBMetric {
  return {
    metricCode: "LAUNCH_PRICE_LEO",
    pillar: 1,
    pillarName: "수송",
    observedOn: "2024-01-01",
    value: 2720,
    unit: "USD_PER_KG",
    basis: "PUBLISHED_PRICE",
    sourceLabel: "Provider price sheet",
    sourceUrl: "https://example.test/p",
    accessedOn: "2026-07-14",
    factSummary: "Published price per kilogram to LEO.",
    ...overrides,
  };
}

describe("LayerBPanel", () => {
  it("renders nothing when there are no metrics", () => {
    const { container } = render(<LayerBPanel metrics={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("labels a published-price estimate distinctly from a measured value", () => {
    render(
      <LayerBPanel
        metrics={[
          metric(),
          metric({ metricCode: "ANNUAL_LAUNCH_COUNT", value: 259, unit: "LAUNCHES", basis: "MEASURED" }),
        ]}
      />,
    );

    expect(screen.getByText("공표 가격 기반 추정")).toBeInTheDocument();
    expect(screen.getByText("측정")).toBeInTheDocument();
    expect(screen.getByText("LAUNCH_PRICE_LEO")).toBeInTheDocument();
    expect(screen.getByText(/2,720/)).toBeInTheDocument();
  });

  it("renders orbital human presence with honest scope precision and provenance", () => {
    render(
      <LayerBPanel
        metrics={[
          metric({
            metricCode: "ANNUAL_ORBITAL_HUMAN_PERSON_DAYS",
            pillar: 2,
            pillarName: "생명 유지",
            observedOn: "2025-12-31",
            value: 3922.2028,
            unit: "PERSON_DAYS",
            basis: "MEASURED",
            sourceLabel: "Jonathan's Space Report orbital population time history",
            sourceUrl: "https://planet4589.org/space/astro/web/pop.html",
            accessedOn: "2026-07-16",
          }),
          metric({
            metricCode: "MAX_SIMULTANEOUS_HUMANS_IN_ORBIT",
            pillar: 2,
            pillarName: "생명 유지",
            observedOn: "2025-12-31",
            value: 14,
            unit: "PEOPLE",
            basis: "MEASURED",
            sourceLabel: "Jonathan's Space Report orbital population time history",
            sourceUrl: "https://planet4589.org/space/astro/web/pop.html",
            accessedOn: "2026-07-16",
          }),
        ]}
      />,
    );

    expect(screen.getByText("연간 궤도 인류 체류")).toBeInTheDocument();
    expect(screen.getByText("3,922.2028 인일")).toBeInTheDocument();
    expect(screen.getByText("연중 최대 동시 궤도 인원")).toBeInTheDocument();
    expect(screen.getByText("14 명")).toBeInTheDocument();
    expect(
      screen.getByText("전 세계 궤도 기준 · 준궤도 제외 · 자동 점수 효과 없음"),
    ).toBeInTheDocument();
    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    rows.forEach(row =>
      expect(row).toHaveTextContent("관측 2025-12-31 · 검수 2026-07-16"),
    );
    const links = screen.getAllByRole("link", { name: "출처 보기" });
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute(
      "href",
      "https://planet4589.org/space/astro/web/pop.html",
    );
    expect(links[0]).toHaveAttribute("target", "_blank");
    expect(links[0]).toHaveAttribute("rel", "noreferrer");
  });
});
