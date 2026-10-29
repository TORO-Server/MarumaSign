#!/usr/bin/env python3
"""
PDF の表示に使う表 (core/src/main/resources/marumasa_sign/pdf) を作る。

使い方:
    python3 tools/pdfdata/gen_pdf_data.py <表の元のフォルダ>

元のフォルダには、次のものを置く (どれも仕様や Adobe が公開している表で、PDF の読み手が共通に使うもの)。
    afm/*.afm          PDF の標準 14 フォントの寸法 (Adobe Core14 AFM)
    glyphlist/*.txt    グリフの名前と Unicode の対応 (Adobe Glyph List、ZapfDingbats の表、追加の表)
    cmap/*             CJK の定義済みの CMap (Adobe cmap-resources)
    tables/*.java      文字コードの表 (PDF 仕様の附属書 D と、CFF 仕様の附属書 A から C) を書き写した Java のソース。
                       参考にした PDF の読み手 (Apache PDFBox 2.0) のソースにあるものを使った:
                       pdmodel/font/encoding/*Encoding.java, fontbox/cff/CFFStandardString.java,
                       fontbox/cff/CFFExpert*.java

作るもの:
    encodings.txt      文字コードの表と、CFF の標準の文字列・文字集合
    glyphlist.txt.gz   グリフの名前と Unicode
    afm.txt.gz         標準 14 フォントの寸法 (必要な項目だけ。著作権の表示は残す)
    cmaps.bin.gz       定義済みの CMap (範囲にまとめて差分で書いた形。形は PredefinedCMaps.java を参照)

ライブラリは使わない (Python の標準の機能だけ)。
"""
import gzip
import os
import re
import struct
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "core", "src", "main", "resources", "marumasa_sign", "pdf")


def write_gz(name, data):
    # 何度作っても同じバイト列になるよう、時刻は 0 にする
    with open(os.path.join(OUT, name), "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as f:
            f.write(data)


def read(path):
    with open(path, "rb") as f:
        return f.read().decode("latin-1")


def java_pairs(path, name_pattern=r'"([^"]*)"'):
    """{ 0101, "A" } の形の組を、(コード, 名前) の並びにする。"""
    text = read(path)
    pairs = []
    for code, name in re.findall(r"\{\s*(0[0-7]*|\d+)\s*,\s*" + name_pattern + r"\s*\}", text):
        pairs.append((int(code, 8) if code.startswith("0") and code != "0" else int(code), name))
    return pairs


def encoding(tables, name):
    names = ["."] * 256
    for code, glyph in java_pairs(os.path.join(tables, name + "Encoding.java")):
        if 0 <= code < 256 and names[code] == ".":
            names[code] = glyph
    return names


def gen_encodings(tables):
    lines = ["# 文字コードの表 (tools/pdfdata/gen_pdf_data.py で作ったもの)。'.' は割り当てのないコード"]
    standard = encoding(tables, "Standard")
    win = encoding(tables, "WinAnsi")
    # WinAnsiEncoding では、041 より上の使われていないコードは bullet (PDF 仕様の附属書 D の注)
    for code in range(0o41, 256):
        if win[code] == ".":
            win[code] = "bullet"
    mac = encoding(tables, "MacRoman")
    mac_os = list(mac)
    for code, glyph in java_pairs(os.path.join(tables, "MacOSRomanEncoding.java")):
        mac_os[code] = glyph
    expert = encoding(tables, "MacExpert")
    symbol = encoding(tables, "Symbol")
    dingbats = encoding(tables, "ZapfDingbats")
    for title, names in (("Standard", standard), ("WinAnsi", win), ("MacRoman", mac), ("MacOSRoman", mac_os),
                         ("MacExpert", expert), ("Symbol", symbol), ("ZapfDingbats", dingbats)):
        lines.append("encoding " + title)
        lines.append(" ".join(names))
    # CFF の標準の文字列 (391 個)
    strings = re.findall(r'^\s*"([^"]*)",?\s*$', read(os.path.join(tables, "CFFStandardString.java")), re.M)
    assert len(strings) == 391, len(strings)
    lines.append("cffstrings")
    lines.append(" ".join(strings))
    for title, file in (("cffexpertcharset", "CFFExpertCharset.java"), ("cffexpertsubsetcharset", "CFFExpertSubsetCharset.java")):
        sids = [int(code) for code, _ in re.findall(r'\{\s*(\d+)\s*,\s*"([^"]*)"\s*\}', read(os.path.join(tables, file)))]
        lines.append(title)
        lines.append(" ".join(str(s) for s in sids))
    expert_encoding = [0] * 256
    for code, sid in re.findall(r"\{\s*(\d+)\s*,\s*(\d+)\s*\}", read(os.path.join(tables, "CFFExpertEncoding.java"))):
        expert_encoding[int(code)] = int(sid)
    lines.append("cffexpertencoding")
    lines.append(" ".join(str(s) for s in expert_encoding))
    with open(os.path.join(OUT, "encodings.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def gen_glyphlist(source):
    entries = {}
    order = []
    # 後ろのものほど優先する (ZapfDingbats の表は、ZapfDingbats のフォントでだけ使うので別に印を付ける)
    for file, prefix in (("glyphlist.txt", ""), ("additional.txt", ""), ("zapfdingbats.txt", "Z ")):
        for line in read(os.path.join(source, file)).splitlines():
            if not line or line.startswith("#"):
                continue
            name, codes = line.split(";")[:2]
            key = prefix + name
            if key not in entries:
                order.append(key)
            entries[key] = codes.strip()
    text = "\n".join(key + ";" + entries[key] for key in order) + "\n"
    write_gz("glyphlist.txt.gz", text.encode("utf-8"))


def gen_afm(source):
    keep = ("Comment Copyright", "FontName", "FamilyName", "Weight", "ItalicAngle", "IsFixedPitch", "FontBBox", "CapHeight",
            "XHeight", "Ascender", "Descender", "StdHW", "StdVW", "C ")
    # AFM の使用条件 (MustRead.html) により、変えたことを書いておく
    out = ["Comment Modified by MarumaSign: only the entries needed to lay out text are kept from the Adobe Core 14 AFM files",
           "Comment (see marumasa_sign/licenses/adobe-core14-afm.txt)"]
    for file in sorted(os.listdir(source)):
        if not file.endswith(".afm"):
            continue
        out.append("StartFont")
        for line in read(os.path.join(source, file)).splitlines():
            line = line.strip()
            if line.startswith(keep):
                if line.startswith("C "):
                    # C 32 ; WX 250 ; N space ; B ... の形から、コード、幅、名前だけ残す
                    fields = dict(part.strip().split(" ", 1) for part in line.split(";") if part.strip() and " " in part.strip())
                    line = "C %s %s %s" % (fields["C"], fields["WX"], fields["N"])
                out.append(line)
        out.append("EndFont")
    write_gz("afm.txt.gz", ("\n".join(out) + "\n").encode("latin-1"))


# ---------- CMap ----------

def tokens(text):
    return re.findall(r"<[0-9a-fA-F\s]*>|\[|\]|/[^\s/\[\]<>()]+|-?\d+|[A-Za-z]+", text)


def parse_cmap(text):
    text = re.sub(r"%[^\n]*", "", text)
    cmap = {"wmode": 0, "use": "", "codespace": [], "entries": [], "unicode": False}
    m = re.search(r"/WMode\s+(\d+)", text)
    if m:
        cmap["wmode"] = int(m.group(1))
    m = re.search(r"/([^\s/]+)\s+usecmap", text)
    if m:
        cmap["use"] = m.group(1)

    def hexbytes(token):
        h = re.sub(r"\s", "", token[1:-1])
        return bytes.fromhex(h + ("0" if len(h) % 2 else ""))

    def first_code_point(data):
        # UTF-16BE の最初の文字
        if len(data) >= 4 and 0xD800 <= int.from_bytes(data[0:2], "big") < 0xDC00:
            hi = int.from_bytes(data[0:2], "big")
            lo = int.from_bytes(data[2:4], "big")
            return 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
        return int.from_bytes(data[0:2], "big") if len(data) >= 2 else (data[0] if data else 0)

    for kind, body in re.findall(r"begin(codespacerange|cidrange|cidchar|bfrange|bfchar|notdefrange|notdefchar)(.*?)end\1", text, re.S):
        t = tokens(body)
        i = 0
        if kind == "codespacerange":
            while i + 1 < len(t):
                low, high = hexbytes(t[i]), hexbytes(t[i + 1])
                cmap["codespace"].append((len(low), int.from_bytes(low, "big"), int.from_bytes(high, "big")))
                i += 2
        elif kind == "cidrange":
            while i + 2 < len(t):
                low, high = hexbytes(t[i]), hexbytes(t[i + 1])
                cmap["entries"].append((len(low), int.from_bytes(low, "big"), int.from_bytes(high, "big"), int(t[i + 2])))
                i += 3
        elif kind == "cidchar":
            while i + 1 < len(t):
                code = hexbytes(t[i])
                value = int.from_bytes(code, "big")
                cmap["entries"].append((len(code), value, value, int(t[i + 1])))
                i += 2
        elif kind == "bfchar":
            cmap["unicode"] = True
            while i + 1 < len(t):
                code = hexbytes(t[i])
                value = int.from_bytes(code, "big")
                if t[i + 1].startswith("<"):
                    cmap["entries"].append((len(code), value, value, first_code_point(hexbytes(t[i + 1]))))
                i += 2
        elif kind == "bfrange":
            cmap["unicode"] = True
            while i + 2 < len(t):
                low, high = hexbytes(t[i]), hexbytes(t[i + 1])
                lo, hi = int.from_bytes(low, "big"), int.from_bytes(high, "big")
                if t[i + 2] == "[":
                    j = i + 3
                    k = 0
                    while j < len(t) and t[j] != "]":
                        cmap["entries"].append((len(low), lo + k, lo + k, first_code_point(hexbytes(t[j]))))
                        j += 1
                        k += 1
                    i = j + 1
                else:
                    cmap["entries"].append((len(low), lo, hi, first_code_point(hexbytes(t[i + 2]))))
                    i += 3
    return cmap


def varint(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(value):
    return (value << 1) if value >= 0 else ((-value << 1) - 1)


def utf(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def gen_cmaps(source):
    out = bytearray(b"MSCM")
    files = sorted(f for f in os.listdir(source) if not f.endswith(".class") and os.path.isfile(os.path.join(source, f)))
    out += struct.pack(">H", len(files))
    for file in files:
        cmap = parse_cmap(read(os.path.join(source, file)))
        entries = sorted(cmap["entries"])
        # 続いている項目 (コードも値も 1 ずつ増えるもの) は 1 つの範囲にまとめる
        merged = []
        for length, low, high, value in entries:
            if merged:
                p = merged[-1]
                if p[0] == length and p[2] + 1 == low and p[3] + (p[2] - p[1]) + 1 == value:
                    merged[-1] = (p[0], p[1], high, p[3])
                    continue
            merged.append((length, low, high, value))
        out += utf(file)
        out += bytes([cmap["wmode"], 1 if cmap["unicode"] else 0])
        out += utf(cmap["use"])
        out += varint(len(cmap["codespace"]))
        for length, low, high in cmap["codespace"]:
            out += bytes([length]) + varint(low) + varint(high)
        out += varint(len(merged))
        previous_code = 0
        previous_value = 0
        previous_length = 0
        for length, low, high, value in merged:
            if length != previous_length:
                previous_code = 0
                previous_length = length
            # (バイト数, 始まりの差, 範囲の長さ - 1, 値の差) を書く
            out += bytes([length]) + varint(zigzag(low - previous_code)) + varint(high - low) + varint(zigzag(value - previous_value))
            previous_code = high
            previous_value = value + (high - low)
    write_gz("cmaps.bin.gz", bytes(out))


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    source = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    gen_encodings(os.path.join(source, "tables"))
    gen_glyphlist(os.path.join(source, "glyphlist"))
    gen_afm(os.path.join(source, "afm"))
    gen_cmaps(os.path.join(source, "cmap"))
    for name in sorted(os.listdir(OUT)):
        print(name, os.path.getsize(os.path.join(OUT, name)))


if __name__ == "__main__":
    main()
