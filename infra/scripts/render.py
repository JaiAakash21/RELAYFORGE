#!/usr/bin/env python3
"""
Renders RelayForge API responses for the demo script.

A separate file rather than `python3 -c` inside the shell script: nesting
Python string quoting inside shell quoting inside a heredoc is a reliable way
to produce a syntax error that only appears at run time, and it did.

Reads JSON on stdin; the first argument selects the view.
"""
import json
import sys
from datetime import datetime


def _parse(ts):
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def attempts(data):
    previous = None
    for attempt in data["attempts"]:
        gap = ""
        if previous:
            delta = (_parse(attempt["startedAt"]) - _parse(previous)).total_seconds()
            gap = "  (+{:.1f}s)".format(delta)
        status = attempt["responseStatus"]
        shown = str(status) if status is not None else "no response"
        print(
            "    #{}  {:<18} http={:<12} {}ms{}".format(
                attempt["attemptNumber"],
                attempt["outcome"],
                shown,
                attempt["durationMs"],
                gap,
            )
        )
        previous = attempt["startedAt"]
    print()
    print("    The gap grows between attempts — that is the exponential backoff,")
    print("    with jitter applied.")


def dead_letters(data):
    print("    {} dead letter(s)".format(data["totalItems"]))
    for item in data["items"]:
        print(
            "    {}  attempts={}  last={}  {}".format(
                item["eventType"],
                item["attemptCount"],
                item["lastResponseCode"],
                item["lastErrorClass"],
            )
        )


def after_replay(data):
    delivery = data["delivery"]
    print(
        "    status={}  attempts={}/{}  replays={}".format(
            delivery["status"],
            delivery["attemptCount"],
            delivery["maxAttempts"],
            delivery["replayCount"],
        )
    )
    for attempt in data["attempts"]:
        status = attempt["responseStatus"]
        shown = str(status) if status is not None else "no response"
        print(
            "    #{}  {:<10} {:<18} http={}".format(
                attempt["attemptNumber"],
                attempt["kind"],
                attempt["outcome"],
                shown,
            )
        )
    print()
    print("    The earlier failures are still there. Replay appended a new attempt")
    print("    rather than erasing the evidence of why the delivery dead-lettered.")


VIEWS = {"attempts": attempts, "dead-letters": dead_letters, "after-replay": after_replay}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in VIEWS:
        print("usage: render.py {}".format("|".join(VIEWS)), file=sys.stderr)
        return 2
    VIEWS[sys.argv[1]](json.load(sys.stdin))
    return 0


if __name__ == "__main__":
    sys.exit(main())
