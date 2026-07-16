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
});
