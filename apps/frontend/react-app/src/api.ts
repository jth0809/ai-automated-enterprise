/**
 * API client for the token-gated résumé and the public news feed.
 * Résumé content is only ever fetched from the backend, never bundled,
 * so it cannot leak through static analysis of the frontend assets.
 */

export interface ResumeExperience {
  company: string;
  role: string;
  period: string;
  highlights: string[];
}

export interface Resume {
  name: string;
  headline?: string;
  summary?: string;
  contact?: { email?: string; location?: string };
  experience?: ResumeExperience[];
  skills?: string[];
}

export interface Article {
  title: string;
  link: string;
  source: string;
  publishedAt: string | null;
  excerpt: string | null;
  summary: string | null;
}

export interface RedeemResult {
  ok: boolean;
  token?: string;
}

export async function redeemCode(code: string): Promise<RedeemResult> {
  const res = await fetch("/api/resume/redeem", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });
  if (!res.ok) return { ok: false };
  const body = (await res.json()) as { token: string };
  return { ok: true, token: body.token };
}

export async function fetchResume(token: string): Promise<Resume> {
  const res = await fetch("/api/resume", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`resume request failed: HTTP ${res.status}`);
  return (await res.json()) as Resume;
}

export async function fetchNews(limit = 20): Promise<Article[]> {
  const res = await fetch(`/api/news?limit=${limit}`);
  if (!res.ok) throw new Error(`news request failed: HTTP ${res.status}`);
  const body = (await res.json()) as { count: number; articles: Article[] };
  return body.articles;
}
