#!/usr/bin/env python3
"""Download IGC logs from an LXNAV S100 / Nano over Bluetooth RFCOMM.

  lxnav_dl.py AA:BB:CC:DD:EE:FF [-c CHANNEL] list
  lxnav_dl.py AA:BB:CC:DD:EE:FF get 6AEVPSA1.igc [-o DIR]
  lxnav_dl.py AA:BB:CC:DD:EE:FF getall [-o DIR]
  lxnav_dl.py AA:BB:CC:DD:EE:FF verify 6AEVPSA1.igc     (FILE_INFO cross-check, small files)

Method: PLXVC,FLIGHT line-range requests (7 lines/block, per-block retry).
Protocol facts observed on an S100 fw 9.27 (see LXNAV DataPort spec 1.04).
"""
import argparse, os, socket, sys, time, zlib

BLOCK = 7            # lines per request (BM77 module: keep <= 7)
TIMEOUT = 4.0        # seconds to wait for a block before re-asking
RETRIES = 8
VERBOSE = False

def xor(s):
    c = 0
    for ch in s:
        c ^= ord(ch)
    return c

def make(body):
    assert " " not in body
    return f"${body}*{xor(body):02X}\r\n".encode()

def parse(line):
    """'$body*CS' -> body, or None if not a valid sentence."""
    line = line.strip()
    if not line.startswith("$") or "*" not in line:
        return None
    body, _, cs = line[1:].rpartition("*")
    try:
        if xor(body) != int(cs[:2], 16):
            return None
    except ValueError:
        return None
    return body

class Link:
    def __init__(self, sock, factory=None):
        self.s = sock
        self.factory = factory
        self.buf = b""
        self.rx = 0                      # total bytes received (diagnostics)
    def reconnect(self):
        try: self.s.close()
        except Exception: pass
        time.sleep(3)
        self.s = self.factory()
        self.buf = b""
    def send(self, body):
        if VERBOSE: print("  >>", body, file=sys.stderr)
        self.s.sendall(make(body))
    def sentences(self, timeout):
        """Yield valid PLXVC/PLXV0 bodies until `timeout` seconds of total time elapse."""
        end = time.monotonic() + timeout
        while True:
            while b"\n" in self.buf:
                raw, self.buf = self.buf.split(b"\n", 1)
                b = parse(raw.decode("ascii", "replace"))
                if b and b.startswith("PLXV"):
                    yield b
            left = end - time.monotonic()
            if left <= 0:
                return
            self.s.settimeout(left)
            try:
                d = self.s.recv(4096)
            except socket.timeout:
                return
            if not d:
                raise ConnectionError("link closed")
            self.rx += len(d)
            self.buf += d
    def drain(self, quiet=0.3):
        self.s.settimeout(quiet)
        try:
            while True:
                d = self.s.recv(4096)
                if not d: break
                self.rx += len(d)
        except socket.timeout:
            pass
        self.buf = b""

def logbook(link):
    """Return [(name, date, start_utc, size)]."""
    for attempt in range(RETRIES):
        link.drain()
        link.send("PLXVC,LOGBOOKSIZE,R")
        n = None
        for b in link.sentences(TIMEOUT):
            f = b.split(",")
            if f[1:3] == ["LOGBOOKSIZE", "A"]:
                n = int(f[3]); break
        if n is None: continue
        if n == 0: return []
        link.send(f"PLXVC,LOGBOOK,R,1,{n + 1}")
        rows = {}
        for b in link.sentences(TIMEOUT):
            f = b.split(",")
            if f[1:3] == ["LOGBOOK", "A"] and len(f) >= 10 and f[3] != "0":
                rows[int(f[3])] = (f[5], f[6], f[7], int(f[9]))
                if len(rows) == n: break
        if len(rows) == n:
            return [rows[i] for i in sorted(rows)]
    raise RuntimeError("could not read logbook")

def get_block(link, name, start, end):
    """Request lines [start,end). Returns (total, {lineno: content})."""
    link.send(f"PLXVC,FLIGHT,R,{name},{start},{end}")
    got, total = {}, None
    deadline_quiet = 0.6
    t_end = time.monotonic() + TIMEOUT
    last = None
    while time.monotonic() < t_end:
        for b in link.sentences(0.2):
            f = b.split(",", 6)         # PLXVC,FLIGHT,A,<empty>,line,total,content
            if len(f) < 7 or f[1:3] != ["FLIGHT", "A"]:
                continue
            try:
                ln, tot = int(f[4]), int(f[5])
            except ValueError:
                continue
            if not (start <= ln < end):
                continue                 # stale reply from an earlier request
            total = tot
            got[ln] = f[6]               # content may itself contain commas
            last = time.monotonic()
        if total is not None:
            want = range(start, min(end, total + 1))
            if all(i in got for i in want):
                return total, got
    return total, got

def load_part(path):
    if not os.path.exists(path): return {}
    with open(path, "rb") as fh:
        ls = fh.read().decode("ascii", "replace").split("\n")
    ls.pop()                                  # last item is "" (clean end) or a truncated line: drop either way
    return {i + 1: l for i, l in enumerate(ls)}

def download(link, name, part=None, progress=True):
    lines = load_part(part) if part else {}
    if lines: print(f"  resuming {name} from line {len(lines) + 1}", file=sys.stderr)
    total, start = None, len(lines) + 1
    pf = open(part, "ab") if part else None
    t0 = time.monotonic()
    fails = 0
    try:
        while total is None or start <= total:
            width = BLOCK if fails < 2 else 1          # shrink to single lines after repeated failure
            end = start + width
            rx0 = link.rx
            tot, got = get_block(link, name, start, end)
            if tot is not None and all(i in got for i in range(start, min(end, tot + 1))):
                total = tot
                for i in range(start, min(end, tot + 1)):
                    lines[i] = got[i]
                    if pf: pf.write(got[i].encode("ascii", "replace") + b"\n")
                if pf: pf.flush()
                start = min(end, tot + 1)
                if fails: fails = max(0, fails - 1)
                if progress:
                    print(f"\r  {name}: {start - 1}/{total} lines  {time.monotonic() - t0:5.0f}s   ", end="", file=sys.stderr, flush=True)
                continue
            fails += 1
            print(f"\n  block {start}-{end - 1} failed (attempt {fails}): {link.rx - rx0} bytes arrived, "
                  f"{len(got)} usable lines, width {width}", file=sys.stderr)
            if fails >= RETRIES:
                raise RuntimeError(f"gave up at line {start}")
            if fails % 3 == 0 and link.factory:
                print("  reconnecting...", file=sys.stderr)
                link.reconnect()
                link.send("PLXVC,FILE_CANCEL,R")
            else:
                link.send("PLXVC,FILE_CANCEL,R")
                time.sleep(1.0 * fails)               # back off
            link.drain(0.5)
    finally:
        if pf: pf.close()
    if progress: print(file=sys.stderr)
    return b"".join(lines[i].encode("ascii", "replace") + b"\r\n" for i in range(1, total + 1))

def report(name, data, expect):
    crc = zlib.crc32(data) & 0xFFFFFFFF
    msg = f"{name}: {len(data)} bytes, CRC32 {crc:08X}"
    if expect is not None:
        if len(data) == expect: msg += f"  size matches logbook ({expect})"
        elif len(data) == expect + 2: msg += f"  logbook size + 2 (extra final CRLF?)"
        else: msg += f"  SIZE MISMATCH, logbook says {expect}"
    print(msg)

def verify(link, name, max_lines=10):
    """FILE_INFO cross-check of the first lines (does not transfer the whole file)."""
    link.send("PLXVC,FILE_CANCEL,R"); link.drain(0.5)
    link.send(f"PLXVC,FILE_INFO,R,/{name},1,100")
    import base64
    n = 0
    out = b""
    while n < max_lines:
        for b in link.sentences(TIMEOUT):
            f = b.split(",")
            if f[1:3] == ["FILE_DATA", "A"]:
                out += base64.b64decode(f[7]); break
        else:
            print("no FILE_DATA reply"); break
        n += 1
        link.send(f"PLXVC,FILE_OK,R,{n}")
    link.send("PLXVC,FILE_CANCEL,R")
    return out

def connect(mac, channel):
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    s.connect((mac, channel))
    return Link(s, factory=lambda: connect(mac, channel).s)

def main():
    global VERBOSE
    ap = argparse.ArgumentParser()
    ap.add_argument("mac"); ap.add_argument("-c", "--channel", type=int, default=1)
    ap.add_argument("-v", "--verbose", action="store_true")
    ap.add_argument("-o", "--out", default=".")
    ap.add_argument("cmd", choices=["list", "get", "getall", "verify"])
    ap.add_argument("name", nargs="?")
    a = ap.parse_args(); VERBOSE = a.verbose
    link = connect(a.mac, a.channel)
    try:
        link.send("PLXVC,FILE_CANCEL,R"); link.drain(0.5)
        book = logbook(link)
        if a.cmd == "list":
            for i, (n, d, t, sz) in enumerate(book, 1):
                print(f"{i:3d}  {n}  {d}  {t} UTC  {sz:>9d} bytes")
            return
        os.makedirs(a.out, exist_ok=True)
        if a.cmd == "verify":
            print(verify(link, a.name).decode("ascii", "replace")); return
        todo = book if a.cmd == "getall" else [r for r in book if r[0].lower() == (a.name or "").lower()]
        if not todo: sys.exit("file not in logbook (try 'list')")
        for n, d, t, sz in todo:
            path = os.path.join(a.out, n)
            if os.path.exists(path) and os.path.getsize(path) in (sz, sz + 2):
                print(f"skip {n} (already have it)"); continue
            part = path + ".part"
            try:
                data = download(link, n, part)
            except (RuntimeError, ConnectionError, OSError) as e:
                print(f"\n{n}: FAILED ({e}). Progress kept in {part}; run the same command again to resume.")
                sys.exit(1)
            report(n, data, sz)
            with open(path, "wb") as fh: fh.write(data)
            os.remove(part)
    finally:
        try: link.send("PLXVC,FILE_CANCEL,R")
        except Exception: pass
        link.s.close()

if __name__ == "__main__":
    main()
