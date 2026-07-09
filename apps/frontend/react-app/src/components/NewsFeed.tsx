import { useEffect, useState } from "react";
import { fetchNews } from "../api";
import type { Article } from "../api";

type FeedState =
  | { phase: "loading" }
  | { phase: "error" }
  | { phase: "ready"; articles: Article[] };

function formatDate(publishedAt: string | null): string | null {
  if (!publishedAt) return null;
  const date = new Date(publishedAt);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

export function NewsFeed() {
  const [state, setState] = useState<FeedState>({ phase: "loading" });

  useEffect(() => {
    let cancelled = false;
    fetchNews()
      .then((articles) => {
        if (!cancelled) setState({ phase: "ready", articles });
      })
      .catch(() => {
        if (!cancelled) setState({ phase: "error" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.phase === "loading") {
    return <p className="muted">Loading the feed…</p>;
  }
  if (state.phase === "error") {
    return (
      <p className="feed-error" role="alert">
        Couldn’t load the feed — please try again later.
      </p>
    );
  }
  if (state.articles.length === 0) {
    return (
      <p className="feed-empty">
        No articles yet — the next ingest run will fill this in.
      </p>
    );
  }

  return (
    <section className="feed">
      {state.articles.map((article) => {
        const date = formatDate(article.publishedAt);
        const body = article.summary ?? article.excerpt;
        return (
          <article className="news-card" key={article.link}>
            <div className="news-meta">
              <span className="news-source">{article.source}</span>
              {date && <time className="news-date">{date}</time>}
              {article.summary && <span className="badge">AI summary</span>}
            </div>
            <h3 className="news-title">
              <a href={article.link} target="_blank" rel="noreferrer">
                {article.title}
              </a>
            </h3>
            {body && <p className="news-body">{body}</p>}
          </article>
        );
      })}
    </section>
  );
}
