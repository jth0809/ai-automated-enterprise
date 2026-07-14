import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReviewFilters } from "./ReviewFilters";

describe("ReviewFilters", () => {
  it("exposes accessible status tabs and the five allowlisted reasons", () => {
    const onStatusChange = vi.fn();
    const onReasonChange = vi.fn();

    render(
      <ReviewFilters
        status="PENDING"
        reason=""
        disabled={false}
        onStatusChange={onStatusChange}
        onReasonChange={onReasonChange}
      />,
    );

    expect(screen.getByRole("button", { name: "Pending" })).toHaveAttribute(
      "aria-pressed", "true",
    );
    fireEvent.click(screen.getByRole("button", { name: "Approved" }));
    expect(onStatusChange).toHaveBeenCalledWith("APPROVED");

    const reason = screen.getByLabelText("Reason filter");
    expect(reason.querySelectorAll("option")).toHaveLength(6);
    fireEvent.change(reason, { target: { value: "CIRCUIT_BREAKER" } });
    expect(onReasonChange).toHaveBeenCalledWith("CIRCUIT_BREAKER");
  });
});
