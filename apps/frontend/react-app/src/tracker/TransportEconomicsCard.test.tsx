import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TransportEconomicsCard } from "./TransportEconomicsCard";
import type { TransportProjection } from "./api";

function projection(
  overrides: Partial<TransportProjection> = {},
): TransportProjection {
  return {
    status: "PROVISIONAL",
    sufficiencyTier: "PROVISIONAL",
    qualificationFlags: [],
    observationCount: 3,
    centralTargetUsdPerKg: 200,
    easyTargetUsdPerKg: 500,
    hardTargetUsdPerKg: 100,
    centralEtaYear: 2098.4,
    earliestEtaYear: 2074.2,
    latestEtaYear: null,
    centralBeyondHorizon: false,
    earliestBeyondHorizon: false,
    latestBeyondHorizon: true,
    priceBasisYear: 2025,
    basis: "PUBLISHED_PRICE",
    priceMeaning: "PUBLISHED_PRICE_DIVIDED_BY_MATCHING_MAX_LEO_PAYLOAD",
    projectionLabel: "Declared-assumption scenario; not provider internal cost",
    intervalKind: "ASSUMPTION_SENSITIVITY",
    coherenceState: "COHERENT",
    coherenceAlertActive: false,
    ...overrides,
  };
}

describe("TransportEconomicsCard", () => {
  it("shows a provisional weak-fit estimate without hiding it", () => {
    render(
      <TransportEconomicsCard
        projection={projection({ qualificationFlags: ["WEAK_FIT"] })}
      />,
    );

    expect(screen.getByText("수송 경제성 시나리오")).toBeInTheDocument();
    expect(screen.getByText(/잠정 3개 관측/)).toBeInTheDocument();
    expect(screen.getByText(/적합도 낮음/)).toBeInTheDocument();
    expect(screen.getByText(/중앙 가정 \$200\/kg/)).toBeInTheDocument();
    expect(screen.getByText(/민감도 \$100–\$500\/kg/)).toBeInTheDocument();
    expect(screen.getByText(/2025 USD/)).toBeInTheDocument();
    expect(
      screen.getByText(/공표가÷동일 구성 최대 LEO 탑재량 — 실제 원가 아님/),
    ).toBeInTheDocument();
    expect(screen.getByText("2098")).toBeInTheDocument();
    expect(screen.getByText(/2074–2175\+/)).toBeInTheDocument();
  });

  it("does not invent a year for non-declining or insufficient data", () => {
    const { rerender } = render(
      <TransportEconomicsCard
        projection={projection({
          status: "NON_DECLINING",
          centralEtaYear: null,
          centralBeyondHorizon: false,
        })}
      />,
    );

    expect(screen.getByText("하락 추세 미확인")).toBeInTheDocument();
    expect(screen.queryByText("2098")).not.toBeInTheDocument();

    rerender(
      <TransportEconomicsCard
        projection={projection({
          status: "INSUFFICIENT_DATA",
          sufficiencyTier: "INSUFFICIENT_DATA",
          observationCount: 2,
          centralEtaYear: null,
          centralBeyondHorizon: false,
        })}
      />,
    );
    expect(screen.getByText("자료 부족")).toBeInTheDocument();
    expect(screen.queryByText("2098")).not.toBeInTheDocument();
  });

  it("renders central and individual sensitivity horizon markers honestly", () => {
    const { rerender } = render(
      <TransportEconomicsCard
        projection={projection({
          status: "BEYOND_HORIZON",
          centralEtaYear: null,
          centralBeyondHorizon: true,
          earliestEtaYear: 2120.2,
          latestEtaYear: 2160.8,
          latestBeyondHorizon: false,
        })}
      />,
    );
    expect(screen.getByText("2175+")).toBeInTheDocument();

    rerender(
      <TransportEconomicsCard
        projection={projection({
          earliestEtaYear: null,
          earliestBeyondHorizon: true,
          latestEtaYear: 2144.6,
          latestBeyondHorizon: false,
        })}
      />,
    );
    expect(screen.getByText(/2175\+–2145/)).toBeInTheDocument();
  });

  it("shows an active divergence warning but not a watch-only state", () => {
    const { rerender } = render(
      <TransportEconomicsCard
        projection={projection({
          coherenceState: "DIVERGENT",
          coherenceAlertActive: true,
        })}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/추세 불일치/);
    expect(screen.getByRole("alert")).toHaveTextContent(/구간 확대/);

    rerender(
      <TransportEconomicsCard
        projection={projection({
          coherenceState: "WATCH",
          coherenceAlertActive: false,
        })}
      />,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
