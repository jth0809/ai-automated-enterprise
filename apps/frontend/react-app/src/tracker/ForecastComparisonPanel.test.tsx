import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ForecastComparisonPanel } from "./ForecastComparisonPanel";
import type {
  ForecastComparison,
  ForecastEstimate,
} from "./api";

const unavailable: ForecastEstimate = {
  status: "NOT_APPLICABLE",
  year: null,
  rawYear: null,
  yearLow: null,
  yearHigh: null,
  relationKind: "NONE",
  label: "적용 범위 밖",
  detail: "이 목표를 직접 계산하지 않습니다.",
  sourceName: null,
  sourceUrl: null,
  sourceLocator: null,
  observedOn: null,
  accessedOn: null,
  legacy: false,
};

function comparison(): ForecastComparison {
  const landingCrowd: ForecastEstimate = {
    ...unavailable,
    status: "AWAITING_AUTHORIZATION",
    relationKind: "DIRECT",
    label: "API 승인 대기",
    detail: "첫 유인 화성 착륙 질문입니다.",
    sourceName: "METACULUS",
    sourceUrl:
      "https://www.metaculus.com/questions/3515/when-will-the-first-humans-land-successfully-on-mars/",
    sourceLocator: "post:3515",
    accessedOn: "2026-07-15",
  };
  const nasaLegacy: ForecastEstimate = {
    ...unavailable,
    status: "LEGACY",
    yearLow: 2030,
    yearHigh: 2039,
    relationKind: "DIRECT",
    label: "NASA 과거 목표",
    detail: "현재 목표가 아닌 역사적 맥락입니다.",
    sourceName: "NASA",
    sourceUrl: "https://www.nasa.gov/example",
    sourceLocator: "2020 release",
    accessedOn: "2026-07-15",
    legacy: true,
  };
  const settlementCrowd: ForecastEstimate = {
    ...landingCrowd,
    status: "CURRENT",
    year: 2090,
    rawYear: 2091.2,
    relationKind: "PROXY",
    label: "90일 이동평균",
    detail: "화성 인구 100명은 자립 정착보다 약한 프록시입니다.",
    sourceUrl: "https://www.metaculus.com/questions/39073/date-mars-population-100/",
    sourceLocator: "post:39073",
    observedOn: "2026-07-15",
  };
  const supporting: ForecastEstimate = {
    ...unavailable,
    status: "SUPPORTING",
    year: 2098.4,
    yearLow: 2074.2,
    relationKind: "SUPPORTING",
    label: "중앙 $200/kg 시나리오",
    detail:
      "$200/kg은 가능 조건이며 사건 날짜가 아닙니다. 민감도는 $100~$500/kg입니다.",
  };
  return {
    status: "PARTIAL",
    asOfDate: "2026-07-15",
    smoothingWindowDays: 90,
    crowdLiveStatus: "AUTHORIZATION_REQUIRED",
    rows: [
      {
        trackCode: "LANDING",
        trackLabel: "첫 유인 화성 착륙",
        definition: "사람이 화성 표면에 처음 성공적으로 착륙",
        model: unavailable,
        transport: supporting,
        crowd: landingCrowd,
        institutional: [nasaLegacy],
      },
      {
        trackCode: "RETURN",
        trackLabel: "유인 화성 임무 귀환",
        definition: "표면 임무 뒤 승무원의 안전 귀환",
        model: unavailable,
        transport: unavailable,
        crowd: { ...unavailable, status: "QUESTION_NOT_SELECTED" },
        institutional: [],
      },
      {
        trackCode: "SETTLEMENT",
        trackLabel: "자립 가능한 화성 정착",
        definition: "핵심 생존 기능을 유지하는 정착 준비",
        model: { ...supporting, status: "DIRECT_PROXY", year: 2140.2 },
        transport: supporting,
        crowd: settlementCrowd,
        institutional: [],
      },
    ],
  };
}

describe("ForecastComparisonPanel", () => {
  it("renders three targets and four explicitly different estimate groups", () => {
    render(<ForecastComparisonPanel comparison={comparison()} />);

    const table = screen.getByRole("table", { name: /화성 예측 비교/ });
    for (const heading of ["목표", "트래커 모델", "수송경제", "군중예측", "기관 목표"]) {
      expect(within(table).getByRole("columnheader", { name: heading })).toBeInTheDocument();
    }
    expect(within(table).getAllByRole("rowheader")).toHaveLength(3);
    expect(screen.getByText("첫 유인 화성 착륙")).toBeInTheDocument();
    expect(screen.getByText("유인 화성 임무 귀환")).toBeInTheDocument();
    expect(screen.getByText("자립 가능한 화성 정착")).toBeInTheDocument();
    expect(screen.getAllByText("해당 없음").length).toBeGreaterThan(0);
    expect(screen.getByText("질문 미선정")).toBeInTheDocument();
    expect(screen.getByText("API 승인 대기")).toBeInTheDocument();
  });

  it("keeps supporting, proxy, legacy, and provenance labels visible", () => {
    render(<ForecastComparisonPanel comparison={comparison()} />);

    expect(screen.getAllByText(/중앙 \$200\/kg 시나리오/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/\$100~\$500\/kg/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/100명.*약한 프록시/).length).toBeGreaterThan(0);
    expect(screen.getByText("과거 목표")).toBeInTheDocument();
    expect(screen.getByText("2030–2039년")).toBeInTheDocument();
    const metaculus = screen.getAllByRole("link", { name: "METACULUS" })[0];
    expect(metaculus).toHaveAttribute("target", "_blank");
    expect(metaculus).toHaveAttribute("rel", "noreferrer");
    expect(metaculus).toHaveAttribute(
      "href",
      expect.stringContaining("metaculus.com/questions/3515"),
    );
    expect(screen.queryByText(/동일한 예측/)).not.toBeInTheDocument();
  });
});
