"""Small, baseline-specific binary XML edits; keep vendor dex and resources intact."""
from pathlib import Path
import json
import re
import struct

ROOT = Path(__file__).resolve().parent


def version():
    data = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))
    if not re.fullmatch(r"[1-9]\d*\.[0-9]\.9\.5", data["versionName"]):
        raise ValueError("versionName must follow <custom major>.<custom revision>.9.5")
    if type(data["versionCode"]) is not int or not 26000009 <= data["versionCode"] < 2100000000:
        raise ValueError("versionCode must exceed all pre-updater builds")
    if (data["amapBaseVersion"] != "9.5" or data["packageName"] != "com.autonavi.amapauto.milu"
            or data["repository"] != "cxcboss/amap-auto-95-custom"
            or data["certificateSha256"] != "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"):
        raise ValueError("Base, package, update channel and signing identity must remain compatible")
    return data


class BinaryXml:
    def __init__(self, data):
        self.chunks = []
        offset = 8
        while offset < len(data):
            kind, header, size = struct.unpack_from("<HHI", data, offset)
            self.chunks.append(bytearray(data[offset:offset + size]))
            offset += size
        pool = self.chunks[0]
        kind, header, size, count, styles, flags, start, style_start = struct.unpack_from("<HHIIIIII", pool)
        if kind != 1 or header != 28 or styles:
            raise ValueError("Unexpected baseline XML string pool")
        self.utf8 = bool(flags & 0x100)
        self.strings = []
        for i in range(count):
            cursor = start + struct.unpack_from("<I", pool, header + i * 4)[0]
            if self.utf8:
                # AXML UTF-8 strings have UTF-16 and UTF-8 length prefixes.
                for _ in range(2):
                    length = pool[cursor]; cursor += 1
                    if length & 0x80:
                        length = ((length & 0x7f) << 8) | pool[cursor]; cursor += 1
                self.strings.append(bytes(pool[cursor:cursor + length]).decode("utf-8"))
            else:
                length = struct.unpack_from("<H", pool, cursor)[0]; cursor += 2
                if length & 0x8000:
                    length = ((length & 0x7fff) << 16) | struct.unpack_from("<H", pool, cursor)[0]; cursor += 2
                self.strings.append(bytes(pool[cursor:cursor + length * 2]).decode("utf-16le"))

    def string(self, text):
        if text not in self.strings:
            self.strings.append(text)
        return self.strings.index(text)

    def encode(self):
        offsets, body = [], bytearray()
        for text in self.strings:
            offsets.append(len(body))
            length = len(text.encode("utf-16le")) // 2
            if self.utf8:
                raw = text.encode("utf-8")
                for n in (length, len(raw)):
                    body.extend(bytes([n]) if n < 128 else bytes([(n >> 8) | 0x80, n & 255]))
                body.extend(raw + b"\0")
            else:
                body.extend(struct.pack("<H", length) if length < 32768 else struct.pack("<HH", (length >> 16) | 0x8000, length & 65535))
                body.extend(text.encode("utf-16le") + b"\0\0")
        body.extend(b"\0" * (-len(body) % 4))
        start = 28 + 4 * len(offsets)
        self.chunks[0] = struct.pack("<HHIIIIII", 1, 28, start + len(body), len(offsets), 0,
                                     0x100 if self.utf8 else 0, start, 0) + struct.pack("<" + "I" * len(offsets), *offsets) + body
        content = b"".join(self.chunks)
        return struct.pack("<HHI", 3, 8, 8 + len(content)) + content


def patch_manifest(data, info):
    xml = BinaryXml(data)
    for chunk in xml.chunks:
        if struct.unpack_from("<H", chunk)[0] != 0x102:
            continue
        if xml.strings[struct.unpack_from("<I", chunk, 20)[0]] != "manifest":
            continue
        attr_start, attr_size, count = struct.unpack_from("<HHH", chunk, 24)
        changed = set()
        for i in range(count):
            pos = 16 + attr_start + i * attr_size
            name = xml.strings[struct.unpack_from("<I", chunk, pos + 4)[0]]
            if name == "versionCode":
                struct.pack_into("<I", chunk, pos + 16, info["versionCode"])
                changed.add(name)
            elif name == "versionName":
                index = xml.string(info["versionName"])
                struct.pack_into("<I", chunk, pos + 8, index)
                struct.pack_into("<I", chunk, pos + 16, index)
                changed.add(name)
        if changed != {"versionName", "versionCode"}:
            raise ValueError("Baseline manifest is missing version attributes")
        return xml.encode()
    raise ValueError("Missing manifest element")


def patch_provider_paths(data):
    xml = BinaryXml(data)
    tag = xml.string("external-path")
    attributes = b"".join(struct.pack("<IIIHBBI", 0xffffffff, xml.string(name), xml.string(value), 8, 0, 3, xml.string(value))
                           for name, value in (("name", "custom-updates"), ("path", "高德更新/")))
    start = struct.pack("<HHIIIIIHHHHHH", 0x102, 16, 36 + len(attributes), 3, 0xffffffff,
                        0xffffffff, tag, 20, 20, 2, 0, 0, 0) + attributes
    end = struct.pack("<HHIIIII", 0x103, 16, 24, 3, 0xffffffff, 0xffffffff, tag)
    closing = next(i for i, chunk in enumerate(xml.chunks)
                   if struct.unpack_from("<H", chunk)[0] == 0x103
                   and xml.strings[struct.unpack_from("<I", chunk, 20)[0]] == "paths")
    xml.chunks[closing:closing] = [start, end]
    return xml.encode()
