import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchNews, fetchResume, redeemCode } from "./api";

function mockFetch(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("redeemCode", () => {
  it("returns the token on a successful redeem", async () => {
    vi.stubGlobal("fetch", mockFetch(200, { token: "abc.def" }));
    const result = await redeemCode("hunter2");
    expect(result).toEqual({ ok: true, token: "abc.def" });
  });

  it("returns ok:false without a token on 401", async () => {
    vi.stubGlobal("fetch", mockFetch(401, { error: "invalid" }));
    const result = await redeemCode("wrong");
    expect(result.ok).toBe(false);
    expect(result.token).toBeUndefined();
  });
});

describe("fetchResume", () => {
  it("sends the token as a Bearer header and returns the résumé", async () => {
    const fetchSpy = mockFetch(200, { name: "Jane Dev" });
    vi.stubGlobal("fetch", fetchSpy);

    const resume = await fetchResume("tok123");

    expect(resume).toEqual({ name: "Jane Dev" });
    const [, init] = fetchSpy.mock.calls[0];
    expect((init as RequestInit).headers).toMatchObject({
      Authorization: "Bearer tok123",
    });
  });

  it("throws on an unauthorized response", async () => {
    vi.stubGlobal("fetch", mockFetch(401, { error: "invalid" }));
    await expect(fetchResume("bad")).rejects.toThrow();
  });
});

describe("fetchNews", () => {
  it("returns the articles array from the feed response", async () => {
    vi.stubGlobal(
      "fetch",
      mockFetch(200, { count: 2, articles: [{ title: "A" }, { title: "B" }] }),
    );
    const articles = await fetchNews();
    expect(articles).toHaveLength(2);
    expect(articles[0].title).toBe("A");
  });
});
