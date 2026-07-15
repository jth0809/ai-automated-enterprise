import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { KIndexCard } from "./KIndexCard";
import type { KIndexSummary } from "./api";

function summary(overrides: Partial<KIndexSummary> = {}): KIndexSummary {
  return {
    status: "CURRENT",
    latestYear: 2024,
    primaryEnergyTwh: 176737.1,
    powerWatts: 20175468036530,
    kValue: 0.7305,
    annualDelta: 0.0011,
    typeOneGap: 0.2695,
    typeOneMultiplier: 495.7,
    accountingBasis: "SUBSTITUTION",
    sourceName: "U.S. EIA; Energy Institute; Our World in Data",
    sourceUrl: "https://ourworldindata.org/grapher/primary-energy-cons",
    accessedOn: "2026-07-15",
    series: [
      { year: 2022, kValue: 0.7285 },
      { year: 2023, kValue: 0.7294 },
      { year: 2024, kValue: 0.7305 },
    ],
    ...overrides,
  };
}

describe("KIndexCard", () => {
  it("shows the current gauge, provenance, and explicit honesty boundary", () => {
    render(<KIndexCard summary={summary()} />);

    expect(
      screen.getByRole("region", { name: "카르다쇼프 K-지수" }),
    ).toBeInTheDocument();
    expect(screen.getByText("인류 문명 지수 K = 0.7305")).toBeInTheDocument();
    expect(
      screen.getByText("구성 게이지 · 연례 관측 · 자동 효과 없음"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Type I까지 ΔK 0.2695 · 현재 에너지의 약 496배"),
    ).toBeInTheDocument();
    expect(screen.getByText("대체법 기준")).toBeInTheDocument();
    expect(screen.getByText("연간 변화 +0.0011")).toBeInTheDocument();
    expect(screen.getByText(/확인일 2026-07-15/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", {
        name: "U.S. EIA; Energy Institute; Our World in Data",
      }),
    ).toHaveAttribute("href", "https://ourworldindata.org/grapher/primary-energy-cons");
    expect(
      screen.getByRole("img", { name: /최근 3개 연례 K-지수 추이/ }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/도달 연도|ETA/)).not.toBeInTheDocument();
  });

  it("warns about stale data without hiding the gauge", () => {
    render(<KIndexCard summary={summary({ status: "STALE", latestYear: 2023 })} />);

    expect(screen.getByText("인류 문명 지수 K = 0.7305")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "최신 관측은 2023년으로 갱신이 필요합니다.",
    );
  });

  it("renders an honest insufficient-data state", () => {
    render(
      <KIndexCard
        summary={summary({
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
        })}
      />,
    );

    expect(screen.getByText("관측 데이터 준비 중")).toBeInTheDocument();
    expect(
      screen.getByText("구성 게이지 · 연례 관측 · 자동 효과 없음"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Type I까지/)).not.toBeInTheDocument();
  });
});
