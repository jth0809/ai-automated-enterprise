import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { GatedResume } from "./GatedResume";

const resume = {
  name: "Jane Dev",
  headline: "Platform Engineer",
  summary: "Builds resilient systems.",
  contact: { email: "jane@example.com", location: "Seoul" },
  experience: [
    {
      company: "Acme Corp",
      role: "Senior Engineer",
      period: "2024 — present",
      highlights: ["Shipped the platform"],
    },
  ],
  skills: ["Kubernetes", "React"],
};

/** Stubs fetch, routing by URL substring; first matching route wins. */
function stubFetch(routes: [string, { status: number; body: unknown }][]) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const match = routes.find(([path]) => url.includes(path));
      if (!match) throw new Error(`unexpected fetch: ${url}`);
      const { status, body } = match[1];
      return {
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      } as Response;
    }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("GatedResume", () => {
  it("asks for an access code and hides the résumé initially", () => {
    render(<GatedResume />);
    expect(screen.getByLabelText(/access code/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /unlock/i })).toBeInTheDocument();
    expect(screen.queryByText("Jane Dev")).not.toBeInTheDocument();
  });

  it("shows the résumé after a valid code is redeemed", async () => {
    stubFetch([
      ["/api/resume/redeem", { status: 200, body: { token: "tok123" } }],
      ["/api/resume", { status: 200, body: resume }],
    ]);
    render(<GatedResume />);

    fireEvent.change(screen.getByLabelText(/access code/i), {
      target: { value: "hunter2" },
    });
    fireEvent.click(screen.getByRole("button", { name: /unlock/i }));

    expect(await screen.findByText("Jane Dev")).toBeInTheDocument();
    expect(screen.getByText("Platform Engineer")).toBeInTheDocument();
    expect(screen.getByText("Acme Corp")).toBeInTheDocument();
    expect(screen.getByText("Kubernetes")).toBeInTheDocument();
    expect(screen.queryByLabelText(/access code/i)).not.toBeInTheDocument();
  });

  it("shows an error and stays locked when the code is rejected", async () => {
    stubFetch([
      ["/api/resume/redeem", { status: 401, body: { error: "invalid" } }],
    ]);
    render(<GatedResume />);

    fireEvent.change(screen.getByLabelText(/access code/i), {
      target: { value: "wrong" },
    });
    fireEvent.click(screen.getByRole("button", { name: /unlock/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/try again/i);
    expect(screen.getByLabelText(/access code/i)).toBeInTheDocument();
    expect(screen.queryByText("Jane Dev")).not.toBeInTheDocument();
  });
});
