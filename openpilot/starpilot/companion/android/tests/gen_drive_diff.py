"""Generates drive-diff-v4.json: seeded bodies for the allow-list shape `drive` with the verdict of the REFERENCE (`problem` of tools/btlab/contract/drive.py of the sidecar repo).

Usage: python3 -I gen_drive_diff.py <path of drive.py> <output json>
Every body is stored as latin-1 text of its raw bytes (`bytes` true when it holds a byte outside 0x20..0x7E), the file is pure ASCII. The seed is fixed: the same file comes out every time.
"""
import importlib.util
import json
import random
import sys

drive = importlib.util.spec_from_file_location("drive_reference", sys.argv[1])
module = importlib.util.module_from_spec(drive)
drive.loader.exec_module(module)

rng = random.Random(20261029)
CHARS = module.TEXT_CHARS
WORDS = module.WORDS


def text():
    return "".join(rng.choice(CHARS) for _ in range(rng.choice([1, 1, 2, 5, 12, 25, 39, 40])))


def valid():
    o = {"v": 1, "seq": rng.choice([0, 1, 7, 1234, 2**30 - 1, 2**31 - 1, rng.randrange(2**31)]), "age_ms": rng.choice([0, 1, 12, 999, 1500, 59999, 60000, rng.randrange(60001)])}
    st = rng.choice([None, *WORDS["st"]])
    if st:
        o["st"] = st
    for key in module.FLAGS:
        if rng.random() < 0.4:
            o[key] = rng.random() < 0.5
    if st == "ovr" and rng.random() < 0.7:
        o["ov"] = rng.choice(WORDS["ov"])
    al = rng.choice([None, 0, 1, 2, 3])
    if al is not None:
        o["al"] = al
    if al and al >= 1:
        if rng.random() < 0.6:
            o["ak"] = rng.choice(WORDS["ak"])
        if rng.random() < 0.5:
            o["a1"] = text()
        if rng.random() < 0.4:
            o["a2"] = text()
    lc = rng.choice([None, *WORDS["lc"]])
    if lc:
        o["lc"] = lc
        if lc != "off" and rng.random() < 0.8:
            o["ld"] = rng.choice(WORDS["ld"])
        if lc == "pre" and rng.random() < 0.7:
            o["lp"] = rng.choice(WORDS["lp"])
    for key in module.ONLY_TRUE:
        if rng.random() < 0.3:
            o[key] = True
    return o


POOL = [None, True, False, 0, 1, 2, 3, 4, -1, 7, 60000, 60001, 2**31 - 1, 2**31, 2**63, 10**30, 1.0, 1.5, -0.0, 1e3, "", "x", "en", "EN", "dis", "l", "r", "man", "steer", "take", "off", "true", "1",
        [], {}, [1], ["en"], {"a": 1}, {"v": 1}, "A" * 40, "A" * 41, "a b", 'a"b', "a\\b", "a\nb", "café", "<b>", "a&b", "a@b", "a#b", "a|b", "a{b", "a~b", "a_b", "a=b", "a*b", "a[b", "a$b", "a^b"]
KEYS = list(module.KEYS)
STRANGE = ["zz", "nav", "ST", "St", "", " st", "st ", "v ", "age", "ms", "al1", "lat2", "a3", "ld ", "x" * 20]


def dump(o, mode):
    if mode == 0:
        return json.dumps(o, separators=(",", ":"), allow_nan=False)
    if mode == 1:
        return json.dumps(o, separators=(", ", ": "))
    if mode == 2:
        return json.dumps(o, separators=(" , ", " : "))
    return json.dumps(o, separators=(",", ":"), ensure_ascii=False)


def object_mutation(o):
    o = dict(o)
    kind = rng.randrange(8)
    keys = list(o)
    if kind == 0 and keys:
        o[rng.choice(keys)] = rng.choice(POOL)
    elif kind == 1 and keys:
        o.pop(rng.choice(keys))
    elif kind == 2:
        o[rng.choice(STRANGE)] = rng.choice(POOL)
    elif kind == 3:
        o[rng.choice(KEYS[3:])] = rng.choice(POOL)
    elif kind == 4:
        # a pair without its partner
        pick = rng.choice([("ld", "l", "lc"), ("lp", "man", "lc"), ("ov", "gas", "st"), ("ak", "lane", "al"), ("a1", "hello", "al"), ("a2", "x", "al")])
        o[pick[0]] = pick[1]
        if rng.random() < 0.5:
            o[pick[2]] = rng.choice(POOL)
    elif kind == 5 and keys:
        order = keys[:]
        rng.shuffle(order)
        o = {k: o[k] for k in order}
    elif kind == 6:
        o["al"] = rng.choice([0, 1, 4, -1, True, 1.0, "1"])
        o["ak"] = rng.choice(WORDS["ak"])
    else:
        o["lc"] = rng.choice(["off", "pre", "go", "fin", "x"])
        o["ld"] = rng.choice(["l", "r", "x"])
        if rng.random() < 0.5:
            o["lp"] = rng.choice(WORDS["lp"])
    return o


def text_mutation(s):
    b = bytearray(s.encode("latin-1", "replace"))
    kind = rng.randrange(9)
    if kind == 0 and b:
        b[rng.randrange(len(b))] = rng.randrange(256)
    elif kind == 1:
        at = rng.randrange(len(b) + 1)
        b[at:at] = bytes([rng.choice([0x20, 0x09, 0x0A, 0x0D, 0x00, 0x7F, 0x80, 0xFF, 0x22, 0x5C, 0x2C, 0x3A])])
    elif kind == 2 and b:
        del b[rng.randrange(len(b)):]
    elif kind == 3:
        b += rng.choice([b"x", b"{}", b" ", b"\x00", b"\n", b",", b"}", b'{"v":1}', b" \t"])
    elif kind == 4:
        b = bytearray(rng.choice([b"\xef\xbb\xbf", b"\xff\xfe", b" "]) + bytes(b))
    elif kind == 5:
        # an integer written another way
        for key in (b'"seq":', b'"age_ms":', b'"v":', b'"al":'):
            at = b.find(key)
            if at >= 0:
                start = at + len(key)
                end = start
                while end < len(b) and chr(b[end]) in "0123456789":
                    end += 1
                if end > start:
                    b[start:end] = rng.choice([b"1e2", b"01", b"-0", b"+1", b"1.0", b"0x10", b"1_0", b"00", b"1E1", b"0.0", b"-1", b"1.", b".5"])
                break
    elif kind == 6:
        # a letter of a string value written as an escape
        at = b.find(b'":"')
        if at >= 0 and at + 3 < len(b):
            ch = b[at + 3]
            b[at + 3:at + 4] = rng.choice([b"\\u%04x" % ch, b"\\u%04X" % ch, b"\\/", b"\\n", b"\\\"", b"\\\\", b"\\u00e9", b"\\ud83d\\ude00", b"\\x41", b"\\"])
    elif kind == 7 and b"," in b:
        at = b.find(b",")
        b[at:at + 1] = rng.choice([b",,", b"", b" ,", b";"])
    else:
        text_ = bytes(b)
        parts = text_.split(b",")
        if len(parts) > 2:
            i = rng.randrange(1, len(parts))
            parts.insert(i, parts[i])
            b = bytearray(b",".join(parts))
    return bytes(b)


cases = []
seen = set()


def add(body, name):
    if body in seen:
        return
    seen.add(body)
    cases.append({"name": name, "body": body.decode("latin-1"), "bytes": any(x > 0x7E or x < 0x20 for x in body), "allow": module.problem(body) == ""})


for _ in range(900):
    o = valid()
    add(dump(o, rng.choice([0, 0, 0, 1, 2, 3])).encode("latin-1", "replace"), "valid")
for _ in range(900):
    o = object_mutation(valid())
    if rng.random() < 0.3:
        o = object_mutation(o)
    add(dump(o, rng.choice([0, 0, 1])).encode("latin-1", "replace"), "object mutation")
for _ in range(900):
    body = dump(valid(), rng.choice([0, 0, 1])).encode("latin-1", "replace")
    for _ in range(rng.choice([1, 1, 2])):
        body = text_mutation(body.decode("latin-1"))
    add(body, "text mutation")
# a body of every length around the bound, and every printable character in a text
base = b'{"v":1,"seq":7,"age_ms":12}'
for size in (500, 510, 511, 512, 513, 514, 600):
    add(base + b" " * (size - len(base)), f"padded to {size}")
for code in range(0x20, 0x7F):
    c = chr(code)
    esc = json.dumps(c)[1:-1]
    add(('{"v":1,"seq":7,"age_ms":12,"al":1,"a1":"x%sx"}' % c).encode("latin-1"), f"raw {code}")
    add(('{"v":1,"seq":7,"age_ms":12,"al":1,"a1":"x%sx"}' % esc).encode("latin-1"), f"escaped {code}")
    add(('{"v":1,"seq":7,"age_ms":12,"al":1,"a1":"x\\u%04xx"}' % code).encode("latin-1"), f"unicode escape {code}")
for code in range(256):
    add(b'{"v":1,"seq":7,"age_ms":12,"al":1,"a1":"x' + bytes([code]) + b'x"}', f"raw byte {code}")

out = {
    "version": 4,
    "what": "generated bodies for the allow-list shape drive with the strict verdict of problem() of drive.py (allow); bytes: the body is the latin-1 text of raw bytes",
    "seed": 20261029,
    "cases": cases,
}
with open(sys.argv[2], "w", encoding="ascii") as handle:
    json.dump(out, handle, ensure_ascii=True, separators=(",", ":"))
    handle.write("\n")
accepted = sum(1 for c in cases if c["allow"])
print(len(cases), "cases,", accepted, "accepted")
