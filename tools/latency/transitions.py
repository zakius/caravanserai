#!/usr/bin/env python3
"""Times dragoman's view transitions on a USB phone: every ordered pair of tabs (first visit and revisit), of the
player's views, and Home to a page and back. Per transition: the frame the finger's release produces, from its vsync
to FrameCompleted (dumpsys gfxinfo framestats), median over the rounds.

Usage: transitions.py [rounds] [out.json] [groups, default first,tab,view,page]. Coordinates are for a 1080x2400 screen at 374 dpi (ASUS Zenfone 10).
Takes the shared phone lock (/tmp/phone-adb.lock) per burst; start with dragoman installed and set up."""
import json, statistics, subprocess, sys, time

PKG = "com.zakius.dragoman"
TABS = {"Home": (108, 2290), "Search": (324, 2290), "Player": (540, 2290), "Library": (756, 2290), "Settings": (972, 2290)}
VIEWS = {"comments": (450, 2060), "lyrics": (630, 2060), "queue": (810, 2060)}  # each slot toggles its view and song
ROW = (500, 465)  # Library's second playlist (the first, Liked Music, is long)


def sh(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, check=True).stdout


def locked(fn):
    with open("/tmp/phone-adb.lock", "w") as lock:
        import fcntl
        fcntl.flock(lock, fcntl.LOCK_EX)
        sh(f"am start -n {PKG}/.MainActivity >/dev/null 2>&1")
        time.sleep(1); hide_ime()
        return fn()


def response(action):
    """Runs [action] and returns the frame its last input event produced (the release), from vsync to FrameCompleted,
    in ms, and that frame's main-thread part (to SyncStart). A tap's frame, not whatever else draws meanwhile."""
    out = sh(f"dumpsys gfxinfo {PKG} reset >/dev/null; {action}; sleep 0.5; dumpsys gfxinfo {PKG} framestats")
    rows, cols = [], None
    for line in out.splitlines():
        if line.startswith("Flags,"):
            cols = line.rstrip(",").split(",")
        elif cols and line and line[0].isdigit() and "," in line:
            r = dict(zip(cols, map(int, line.rstrip(",").split(","))))
            if r["FrameCompleted"] > 0 and r["InputEventId"] != 0:
                rows.append(((r["FrameCompleted"] - r["IntendedVsync"]) / 1e6, (r["SyncStart"] - r["IntendedVsync"]) / 1e6))
        elif line.startswith("---PROFILEDATA---") and rows:
            break  # the window's own data only, not a dialog's
    return rows[-1] if rows else (float("nan"), float("nan"))


def hide_ime():
    """Search opens the keyboard, and adjustResize lifts the nav bar above it: Back closes the keyboard (only it)."""
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown"):
        sh("input keyevent BACK"); time.sleep(0.5)


def tap(xy):
    """Like a finger: down, about 90 ms (input's own start-up adds to the sleep), up. `input tap` sends both at once,
    which a finger never does: the press's own frame then can't wake the GPU before the release's frame."""
    return f"input motionevent DOWN {xy[0]} {xy[1]}; sleep 0.03; input motionevent UP {xy[0]} {xy[1]}"


def circuit(nodes):
    """An order of visits covering every ordered pair of [nodes] once (Hierholzer on the complete digraph)."""
    left = {n: [m for m in nodes if m != n] for n in nodes}
    stack, path = [nodes[0]], []
    while stack:
        if left[stack[-1]]:
            stack.append(left[stack[-1]].pop())
        else:
            path.append(stack.pop())
    return path[::-1]


def warm_tabs(res):
    for t in TABS:  # every tab visited, so each switch only lays out and draws
        sh(tap(TABS[t])); time.sleep(0.6); hide_ime()
    order = circuit(list(TABS))
    sh(tap(TABS[order[0]])); time.sleep(0.6); hide_ime()
    for a, b in zip(order, order[1:]):
        res.setdefault(f"tab {a}->{b}", []).append(response(tap(TABS[b])))
        hide_ime()


def cold_tabs(res, pairs):
    """First visits: each pair starts a fresh process on tab a (restored from the last run), then taps b."""
    for a, b in pairs:
        sh(f"am force-stop {PKG}"); time.sleep(0.3)
        sh(f"am start -W -n {PKG}/.MainActivity >/dev/null"); time.sleep(3); hide_ime()
        res.setdefault(f"first {a}->{b}", []).append(response(tap(TABS[b])))
        time.sleep(1.2)  # wait until ui.tab is saved
        hide_ime()


def views(res):
    sh(tap(TABS["Player"])); time.sleep(0.6)
    order = circuit(["song", *VIEWS])
    for a, b in zip(order, order[1:]):
        res.setdefault(f"view {a}->{b}", []).append(response(tap(VIEWS[b] if b != "song" else VIEWS[a])))
        time.sleep(1.5)  # let the comments and lyrics load before the next switch


def page(res):
    sh(tap(TABS["Library"])); time.sleep(0.6)
    res.setdefault("page Library->page", []).append(response(tap(ROW)))
    time.sleep(1.5)  # let the page load
    res.setdefault("page page->Library", []).append(response("input keyevent BACK"))


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    groups = (sys.argv[3] if len(sys.argv) > 3 else "first,tab,view,page").split(",")
    res = {}
    order = circuit(list(TABS))
    pairs = list(zip(order, order[1:]))
    for _ in range(rounds):
        # Start on the circuit's first tab, so each cold pair begins where the last one left off.
        if "first" in groups:
            locked(lambda: (sh(tap(TABS[order[0]])), time.sleep(1.2), hide_ime(), cold_tabs(res, pairs[:10])))
            locked(lambda: cold_tabs(res, pairs[10:]))
        else:  # each round starts a fresh process, so its first view switches are true first switches
            sh(f"am force-stop {PKG}")
        # The page runs twice: its first open shows a spinner, the second shows the page from memory.
        locked(lambda: [fn(res) for g, fn in (("tab", warm_tabs), ("view", views), ("page", page), ("page", page)) if g in groups])
    med = {k: statistics.median(f for f, _ in v) for k, v in sorted(res.items())}
    main = {k: statistics.median(m for _, m in v) for k, v in res.items()}
    for k, v in med.items():
        print(f"{k:28s} {v:6.1f} main {main[k]:5.1f}  {' '.join(f'{f:.1f}' for f, _ in res[k])}")
    groups = {}
    for k, v in med.items():
        groups.setdefault(k.split()[0], []).append(v)
    for g, vs in groups.items():
        print(f"mean {g:8s} {statistics.mean(vs):6.1f} ms over {len(vs)} transitions")
    print(f"mean all      {statistics.mean(med.values()):6.1f} ms, main thread {statistics.mean(main.values()):5.1f} ms")
    if len(sys.argv) > 2:
        json.dump(res, open(sys.argv[2], "w"), indent=1)


if __name__ == "__main__":
    main()
