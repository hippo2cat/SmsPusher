import { useCallback, useEffect, useMemo, useState } from "react";
import { Copy, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { changeAppLanguage } from "./i18n";
import { getSettings, listMessages, listenToServiceEvents } from "./tauri";
import type { MessageSnapshot } from "./types";

function codeText(message?: MessageSnapshot | null) {
  return message?.verificationCode ?? "";
}

function preview(message: MessageSnapshot) {
  return message.body.replace(/\s+/g, " ").trim();
}

function formatDate(value: string) {
  return new Date(value).toLocaleString();
}

export default function HistoryApp() {
  const { t } = useTranslation();
  const [messages, setMessages] = useState<MessageSnapshot[]>([]);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [copyFeedback, setCopyFeedback] = useState("");

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return messages;
    return messages.filter((message) => {
      return (
        message.sender.toLowerCase().includes(normalized) ||
        message.body.toLowerCase().includes(normalized) ||
        codeText(message).toLowerCase().includes(normalized)
      );
    });
  }, [messages, query]);

  const selected =
    filtered.find((message) => message.messageId === selectedId) ?? filtered[0] ?? null;

  const loadMessages = useCallback(async (selectingMessageId = selectedId) => {
    const next = await listMessages();
    setMessages(next);
    if (selectingMessageId && next.some((message) => message.messageId === selectingMessageId)) {
      setSelectedId(selectingMessageId);
    } else {
      setSelectedId(next[0]?.messageId ?? null);
    }
    setStatus("");
  }, [selectedId]);

  useEffect(() => {
    loadMessages().catch((error) => setStatus(String(error)));
  }, [loadMessages]);

  useEffect(() => {
    getSettings()
      .then((settings) => changeAppLanguage(settings.languagePreference))
      .catch((error) => setStatus(String(error)));
  }, []);

  useEffect(() => {
    let dispose: (() => void) | undefined;
    listenToServiceEvents(() => {
      loadMessages().catch((error) => setStatus(String(error)));
    }).then((unlisten) => {
      dispose = unlisten;
    });
    return () => dispose?.();
  }, [loadMessages]);

  async function copyText(value: string | undefined | null, label: string) {
    if (!value) return;
    await navigator.clipboard.writeText(value);
    setCopyFeedback(t("history.copied", { label }));
    window.setTimeout(() => setCopyFeedback(""), 1600);
  }

  return (
    <main className="history-shell">
      <header className="toolbar">
        <h1>{t("history.title")}</h1>
        <div className="toolbar-actions">
          <input
            type="search"
            placeholder={t("history.searchPlaceholder")}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            autoComplete="off"
          />
          <button type="button" onClick={() => loadMessages().catch((error) => setStatus(String(error)))}>
            <RefreshCw size={15} />
            {t("history.refresh")}
          </button>
        </div>
      </header>

      <section className="history-layout" aria-label={t("history.title")}>
        <div className="message-list">
          {filtered.length > 0 ? (
            <table className="history-table">
              <thead>
                <tr>
                  <th scope="col">{t("history.sender")}</th>
                  <th scope="col">{t("history.time")}</th>
                  <th scope="col">{t("history.code")}</th>
                  <th scope="col">{t("history.message")}</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((message) => (
                  <tr
                    key={message.messageId}
                    tabIndex={0}
                    className={message.messageId === selected?.messageId ? "selected" : ""}
                    onClick={() => setSelectedId(message.messageId)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        setSelectedId(message.messageId);
                      }
                    }}
                  >
                    <td>{message.sender}</td>
                    <td>{formatDate(message.receivedAt)}</td>
                    <td>{codeText(message)}</td>
                    <td>{preview(message)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="empty-state">
              <h2>{messages.length === 0 ? t("history.noMessages") : t("history.noMatches")}</h2>
              <p>
                {messages.length === 0
                  ? t("history.emptyDetail")
                  : t("history.tryDifferentSearch")}
              </p>
            </div>
          )}
        </div>

        <aside className="detail-pane" aria-label={t("history.message")}>
          <div className="detail-heading">
            <div>
              <p className="label">{t("history.sender")}</p>
              <h2>{selected?.sender ?? t("history.noMessages")}</h2>
            </div>
            <button
              type="button"
              disabled={!selected}
              onClick={() => copyText(selected?.sender, t("history.sender"))}
            >
              <Copy size={15} />
              {t("history.copySender")}
            </button>
          </div>

          <dl className="metadata">
            <div>
              <dt>{t("history.time")}</dt>
              <dd>{selected ? formatDate(selected.receivedAt) : "-"}</dd>
            </div>
            <div>
              <dt>{t("history.device")}</dt>
              <dd>{selected?.deviceId ?? "-"}</dd>
            </div>
          </dl>

          {selected && codeText(selected) ? (
            <div className="code-row">
              <div>
                <p className="label">{t("history.code")}</p>
                <p className="verification-code">{codeText(selected)}</p>
              </div>
              <button type="button" onClick={() => copyText(codeText(selected), t("history.code"))}>
                <Copy size={15} />
                {t("history.copyCode")}
              </button>
            </div>
          ) : null}

          <div className="body-block">
            <p className="label">{t("history.message")}</p>
            <pre id="detail-body">
              {selected?.body ?? t("history.emptyDetail")}
            </pre>
          </div>

          <div className="detail-actions">
            <button
              type="button"
              disabled={!selected}
              onClick={() => copyText(selected?.body, t("history.message"))}
            >
              <Copy size={15} />
              {t("history.copyBody")}
            </button>
            <p className="status-line" role="status">
              {copyFeedback || status || t("history.count", { count: messages.length })}
            </p>
          </div>
        </aside>
      </section>
    </main>
  );
}
