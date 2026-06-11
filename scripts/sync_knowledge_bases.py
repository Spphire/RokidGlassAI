#!/usr/bin/env python3
"""Generate compact Android knowledge-base assets from local folders."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


DEFAULT_SOFTWARE_ROOT = Path(r"C:\Users\Apricity\Desktop\软件工程课程小作业")
DEFAULT_FRENCH_ROOT = Path(r"F:\tcftef")
DEFAULT_OUTPUT_DIR = Path("phone-app/src/main/assets/knowledge_bases")

SUPPORTED_TEXT_EXTENSIONS = {".txt", ".md", ".markdown"}
SUPPORTED_PDF_EXTENSIONS = {".pdf"}
WINDOWS_ABSOLUTE_PATH_RE = re.compile(r"\b[A-Za-z]:[\\/][^\s\]\)`<>'\"]+")
SKIP_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".local_pkgs",
    ".venv",
    "__pycache__",
    "build",
    "node_modules",
}


@dataclass(frozen=True)
class KnowledgeBaseSource:
    kb_id: str
    name: str
    description: str
    root: Path
    output_name: str


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract .txt/.md/.pdf files into compact phone-app assets."
    )
    parser.add_argument("--software-root", type=Path, default=DEFAULT_SOFTWARE_ROOT)
    parser.add_argument("--french-root", type=Path, default=DEFAULT_FRENCH_ROOT)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--max-kb-chars", type=int, default=240_000)
    parser.add_argument("--max-source-chars", type=int, default=80_000)
    parser.add_argument("--chunk-chars", type=int, default=1_800)
    parser.add_argument("--chunk-overlap", type=int, default=160)
    args = parser.parse_args()

    sources = [
        KnowledgeBaseSource(
            kb_id="software_engineering",
            name="Software Engineering",
            description="Course notes, review material, and assignment references.",
            root=args.software_root,
            output_name="software_engineering.json",
        ),
        KnowledgeBaseSource(
            kb_id="french",
            name="French TCF/TEF",
            description="French reading, vocabulary, TCF/TEF practice, and OCR notes.",
            root=args.french_root,
            output_name="french.json",
        ),
    ]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest_profiles = []
    for source in sources:
        snapshot = build_snapshot(
            source=source,
            max_kb_chars=args.max_kb_chars,
            max_source_chars=args.max_source_chars,
            chunk_chars=args.chunk_chars,
            chunk_overlap=args.chunk_overlap,
        )
        output_path = args.output_dir / source.output_name
        output_path.write_text(
            json.dumps(snapshot, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        manifest_profiles.append(
            {
                "id": source.kb_id,
                "name": source.name,
                "description": source.description,
                "asset": f"knowledge_bases/{source.output_name}",
                "chunkCount": len(snapshot["chunks"]),
                "includedChars": snapshot["includedChars"],
            }
        )
        print(
            f"{source.name}: {len(snapshot['documents'])} docs, "
            f"{len(snapshot['chunks'])} chunks, {snapshot['includedChars']} chars"
        )

    manifest = {
        "version": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "profiles": manifest_profiles,
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def build_snapshot(
    source: KnowledgeBaseSource,
    max_kb_chars: int,
    max_source_chars: int,
    chunk_chars: int,
    chunk_overlap: int,
) -> dict:
    documents = []
    chunks = []
    included_chars = 0
    rank = 0

    for file_path in iter_source_files(source.root):
        if included_chars >= max_kb_chars:
            break

        extracted = extract_file_text(file_path)
        if not extracted:
            continue

        text = sanitize_local_paths(normalize_text(extracted), (source.root,))[:max_source_chars]
        if len(text) < 80:
            continue

        relative_path = safe_relative(file_path, source.root)
        document_index = len(documents)
        documents.append(
            {
                "title": file_path.stem,
                "source": relative_path,
                "extension": file_path.suffix.lower(),
                "includedChars": min(len(text), max_kb_chars - included_chars),
            }
        )

        for chunk_index, chunk_text in enumerate(
            chunk_text_by_chars(text, chunk_chars, chunk_overlap)
        ):
            if included_chars >= max_kb_chars:
                break
            budget = max_kb_chars - included_chars
            if len(chunk_text) > budget:
                chunk_text = chunk_text[:budget].rstrip()
            if len(chunk_text) < 80:
                continue
            chunks.append(
                {
                    "id": f"{source.kb_id}-{document_index:03d}-{chunk_index:03d}",
                    "title": file_path.stem,
                    "source": relative_path,
                    "rank": rank,
                    "text": chunk_text,
                }
            )
            rank += 1
            included_chars += len(chunk_text)

    return {
        "id": source.kb_id,
        "name": source.name,
        "description": source.description,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "includedChars": included_chars,
        "documents": documents,
        "chunks": chunks,
    }


def iter_source_files(root: Path) -> Iterable[Path]:
    if not root.exists():
        print(f"warning: missing knowledge base root: {root}")
        return []

    files: list[Path] = []
    for file_path in root.rglob("*"):
        if not file_path.is_file():
            continue
        if any(part in SKIP_DIR_NAMES for part in file_path.parts):
            continue
        suffix = file_path.suffix.lower()
        if suffix in SUPPORTED_TEXT_EXTENSIONS or suffix in SUPPORTED_PDF_EXTENSIONS:
            files.append(file_path)

    def priority(path: Path) -> tuple[int, int, str]:
        suffix = path.suffix.lower()
        type_rank = 0 if suffix in SUPPORTED_TEXT_EXTENSIONS else 1
        size_rank = min(path.stat().st_size, 100_000_000)
        return (type_rank, size_rank, str(path).lower())

    return sorted(files, key=priority)


def extract_file_text(path: Path) -> str:
    suffix = path.suffix.lower()
    try:
        if suffix in SUPPORTED_TEXT_EXTENSIONS:
            return read_text_file(path)
        if suffix in SUPPORTED_PDF_EXTENSIONS:
            return extract_pdf_text(path)
    except Exception as exc:  # noqa: BLE001
        print(f"warning: skipped {path}: {exc}")
    return ""


def read_text_file(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "gb18030", "latin-1"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_bytes().decode("utf-8", errors="ignore")


def extract_pdf_text(path: Path) -> str:
    try:
        import fitz  # type: ignore

        parts = []
        with fitz.open(path) as document:
            for page_index, page in enumerate(document):
                text = page.get_text("text").strip()
                if text:
                    parts.append(f"[Page {page_index + 1}]\n{text}")
        return "\n\n".join(parts)
    except ImportError:
        pass

    from pypdf import PdfReader  # type: ignore

    reader = PdfReader(str(path))
    parts = []
    for page_index, page in enumerate(reader.pages):
        text = (page.extract_text() or "").strip()
        if text:
            parts.append(f"[Page {page_index + 1}]\n{text}")
    return "\n\n".join(parts)


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def sanitize_local_paths(text: str, known_roots: Iterable[Path]) -> str:
    sanitized = text
    for root in known_roots:
        root_pattern = windows_path_pattern(root)
        if not root_pattern:
            continue
        known_root_re = re.compile(
            root_pattern + r"(?P<tail>(?:[\\/][^\s\]\)`<>'\"]+)*)",
            re.IGNORECASE,
        )

        def replace_known_root(match: re.Match[str]) -> str:
            tail = match.group("tail").lstrip("\\/")
            if not tail:
                return root.name
            return tail.replace("\\", "/")

        sanitized = known_root_re.sub(replace_known_root, sanitized)

    return WINDOWS_ABSOLUTE_PATH_RE.sub(redact_windows_path, sanitized)


def windows_path_pattern(path: Path) -> str:
    parts = [part.strip("\\/") for part in path.parts if part.strip("\\/")]
    return r"[\\/]+".join(re.escape(part) for part in parts)


def redact_windows_path(match: re.Match[str]) -> str:
    raw_path = match.group(0)
    trailing = ""
    while raw_path and raw_path[-1] in ".,;:，。；：":
        trailing = raw_path[-1] + trailing
        raw_path = raw_path[:-1]

    leaf = re.split(r"[\\/]", raw_path.rstrip("\\/"))[-1]
    return (leaf or "[local path]") + trailing


def chunk_text_by_chars(text: str, chunk_chars: int, overlap: int) -> Iterable[str]:
    if len(text) <= chunk_chars:
        yield text.strip()
        return

    start = 0
    while start < len(text):
        end = min(start + chunk_chars, len(text))
        if end < len(text):
            newline = text.rfind("\n\n", start + chunk_chars // 2, end)
            if newline > start:
                end = newline
        chunk = text[start:end].strip()
        if chunk:
            yield chunk
        if end >= len(text):
            break
        start = max(end - overlap, start + 1)


def safe_relative(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


if __name__ == "__main__":
    main()
