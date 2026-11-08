"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { AlertTriangle, Check, Info, X } from "lucide-react";
import { cn } from "@/lib/cn";

/**
 * Toast notifications.
 *
 * <h2>Accessibility, which is the whole reason this is not a div with a timer</h2>
 *
 * The container is an `aria-live` region, so a screen reader announces a toast
 * the moment it appears. Success messages use `polite` (they wait for a pause);
 * errors use `assertive` (they interrupt), because an error the user does not
 * hear is an error they will act as though never happened.
 *
 * <p>Errors also do not auto-dismiss. A success toast vanishing after three
 * seconds is fine — the user saw the thing succeed. An error vanishing before it
 * has been read loses the only explanation of what went wrong.
 */

type ToastTone = "success" | "error" | "info";

interface Toast {
  id: string;
  tone: ToastTone;
  title: string;
  description?: string;
  /** Extra line, used for the trace id on failures. */
  meta?: string;
}

interface ToastContextValue {
  success: (title: string, description?: string) => void;
  error: (title: string, description?: string, meta?: string) => void;
  info: (title: string, description?: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error("useToast must be used inside <ToastProvider>");
  }
  return context;
}

const AUTO_DISMISS_MS: Record<ToastTone, number | null> = {
  success: 3500,
  info: 5000,
  // Never. See the class comment.
  error: null,
};

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (toast: Omit<Toast, "id">) => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      // Cap the stack. Without this, a loop that errors on every iteration
      // fills the screen and hides the page underneath.
      setToasts((current) => [...current.slice(-4), { ...toast, id }]);

      const delay = AUTO_DISMISS_MS[toast.tone];
      if (delay !== null) {
        timers.current.set(
          id,
          setTimeout(() => dismiss(id), delay),
        );
      }
    },
    [dismiss],
  );

  // Clear outstanding timers on unmount, so a dismiss cannot fire against an
  // unmounted component.
  useEffect(() => {
    const map = timers.current;
    return () => {
      map.forEach((timer) => clearTimeout(timer));
      map.clear();
    };
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({
      success: (title, description) => push({ tone: "success", title, description }),
      error: (title, description, meta) =>
        push({ tone: "error", title, description, meta }),
      info: (title, description) => push({ tone: "info", title, description }),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

const TONE_STYLES: Record<ToastTone, { icon: React.ReactNode; className: string }> = {
  success: {
    icon: <Check className="h-3.5 w-3.5" aria-hidden />,
    className: "border-status-success-border bg-status-success-bg text-status-success",
  },
  error: {
    icon: <AlertTriangle className="h-3.5 w-3.5" aria-hidden />,
    className: "border-status-failed-border bg-status-failed-bg text-status-failed",
  },
  info: {
    icon: <Info className="h-3.5 w-3.5" aria-hidden />,
    className: "border-status-pending-border bg-status-pending-bg text-status-pending",
  },
};

function ToastViewport({
  toasts,
  onDismiss,
}: {
  toasts: Toast[];
  onDismiss: (id: string) => void;
}) {
  return (
    <div
      // Fixed, above everything, and pointer-events-none on the container so it
      // never blocks clicks on the page behind it; the cards re-enable them.
      className="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col items-center gap-2 p-4 sm:inset-x-auto sm:right-0 sm:items-end"
    >
      {toasts.map((toast) => {
        const tone = TONE_STYLES[toast.tone];
        return (
          <div
            key={toast.id}
            role={toast.tone === "error" ? "alert" : "status"}
            aria-live={toast.tone === "error" ? "assertive" : "polite"}
            className="pointer-events-auto w-full max-w-sm animate-slide-in-right rounded-lg border border-border bg-surface-3 p-3 shadow-overlay"
          >
            <div className="flex items-start gap-2.5">
              <span
                className={cn(
                  "mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border",
                  tone.className,
                )}
              >
                {tone.icon}
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-content">{toast.title}</p>
                {toast.description ? (
                  <p className="mt-0.5 break-words text-xs leading-relaxed text-content-secondary">
                    {toast.description}
                  </p>
                ) : null}
                {toast.meta ? (
                  <p className="rf-mono mt-1.5 text-content-tertiary">{toast.meta}</p>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => onDismiss(toast.id)}
                aria-label="Dismiss notification"
                className="-m-1 rounded p-1 text-content-tertiary transition-colors hover:bg-surface-4 hover:text-content"
              >
                <X className="h-3.5 w-3.5" aria-hidden />
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
