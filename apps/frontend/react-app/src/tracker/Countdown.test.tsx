import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Countdown } from "./Countdown";

describe("Countdown", () => {
  it("renders the big year, the 80% interval, and the fixed honesty label", () => {
    render(
      <Countdown
        etaYear={2048.3}
        etaLow={2042}
        etaHigh={2056}
        label="현 추세 지속 시나리오 기준 · 모델 내 80% 구간"
      />,
    );

    expect(screen.getByText("2048")).toBeInTheDocument();
    expect(screen.getByText(/2042\s*–\s*2056/)).toBeInTheDocument();
    expect(
      screen.getByText("현 추세 지속 시나리오 기준 · 모델 내 80% 구간"),
    ).toBeInTheDocument();
  });

  it("renders the beyond-horizon marker when the eta is unresolved", () => {
    render(
      <Countdown
        etaYear={null}
        etaLow={null}
        etaHigh={null}
        label="현 추세 지속 시나리오 기준 · 모델 내 80% 구간"
      />,
    );

    expect(screen.getByText("2175+")).toBeInTheDocument();
  });
});
