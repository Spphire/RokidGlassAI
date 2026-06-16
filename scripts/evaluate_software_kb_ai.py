#!/usr/bin/env python3
"""Evaluate software-engineering knowledge-base retrieval with 15 relay calls.

The script intentionally mirrors the phone app's lightweight retrieval strategy:
query planning, concept expansion, reciprocal-rank fusion, and MMR-style
diversity. It writes reports under build/, which is ignored by git.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "phone-app" / "src" / "main" / "assets" / "knowledge_bases"
REPORT_DIR = ROOT / "build" / "reports" / "knowledge_eval"
MAX_CONTEXT_CHARS = 10_000
MAX_CONTEXT_CHUNKS = 6
MAX_QUERY_TERMS = 24
RRF_K = 48.0
MMR_RELEVANCE_WEIGHT = 0.78


STOP_WORDS = {
    "the",
    "and",
    "for",
    "with",
    "this",
    "that",
    "answer",
    "image",
    "prompt",
    "example",
    "如果",
    "回答",
    "题目",
    "图片",
    "图中",
    "正确",
    "选项",
    "解释",
    "软件",
    "工程",
    "项目",
    "课程",
    "期末",
    "说明",
    "什么",
    "如何",
    "分别",
    "哪些",
    "问题",
    "解决",
    "应该",
    "对应",
    "影响",
    "请",
    "举例",
    "系统",
}


CONCEPT_EXPANSIONS = {
    "process": [
        "软件过程",
        "软件生命周期",
        "生命周期",
        "瀑布",
        "螺旋",
        "rup",
        "scrum",
        "xp",
        "敏捷",
        "迭代",
        "增量",
        "过程模型",
        "software process",
        "process model",
        "agile",
        "iterative",
    ],
    "requirements": [
        "需求",
        "需求工程",
        "需求获取",
        "需求分析",
        "需求定义",
        "需求验证",
        "可验证",
        "问题陈述",
        "软件需求规约",
        "srs",
        "vision",
        "stakeholder",
        "use case",
        "用例",
        "requirement",
        "requirements",
    ],
    "design": [
        "软件设计",
        "架构",
        "模块",
        "耦合",
        "内聚",
        "高内聚",
        "低耦合",
        "uml",
        "类图",
        "顺序图",
        "architecture",
        "coupling",
        "cohesion",
    ],
    "testing": [
        "软件测试",
        "测试",
        "黑盒",
        "白盒",
        "等价类",
        "边界值",
        "覆盖率",
        "单元测试",
        "集成测试",
        "test",
        "testing",
    ],
    "project_management": [
        "项目管理",
        "软件项目管理",
        "团队管理",
    ],
    "estimation": [
        "估算",
        "成本",
        "进度",
        "人月",
        "cocomo",
        "工作量",
        "规模估算",
        "cost estimation",
    ],
    "risk_management": [
        "风险分析",
        "风险管理",
        "风险识别",
        "风险应对",
        "风险监控",
        "什么风险",
        "属于什么风险",
        "risk",
    ],
    "quality": [
        "软件质量",
        "质量属性",
        "质量管理",
        "可靠性",
        "可维护性",
        "cmmi",
        "spice",
        "iso",
        "度量",
        "quality",
    ],
    "new_progress": [
        "软件工程新进展",
        "新进展",
        "ai for se",
        "se for ai",
        "人工智能",
        "大模型",
    ],
    "french_exam": [
        "tcf",
        "tef",
        "compréhension",
        "compréhension écrite",
        "vocabulaire",
        "grammaire",
        "français",
        "question",
        "选项",
    ],
}


TOPIC_MARKERS = {
    "process": [
        "02 软件过程",
        "软件过程",
        "过程模型",
        "生命周期",
        "rup",
        "scrum",
        "xp",
        "瀑布",
        "敏捷",
        "迭代",
    ],
    "requirements": [
        "03 软件需求",
        "online-exam-usecase",
        "软件需求",
        "需求",
        "vision",
        "stakeholder",
        "use case",
        "用例",
        "问题陈述",
    ],
    "design": [
        "04 软件设计",
        "软件设计",
        "架构",
        "耦合",
        "内聚",
        "uml",
        "类图",
        "顺序图",
        "微服务",
    ],
    "testing": [
        "06 软件测试",
        "软件测试",
        "测试",
        "黑盒",
        "白盒",
        "等价类",
        "边界值",
        "覆盖",
    ],
    "project_management": [
        "07 软件项目管理",
        "软件项目管理",
        "项目管理",
    ],
    "estimation": [
        "5-14",
        "估算案例",
        "估算作业",
        "估算",
        "cocomo",
        "人月",
        "工作量",
    ],
    "risk_management": [
        "09 软件风险管理",
        "5-28",
        "风险分析",
        "风险管理",
        "风险",
    ],
    "quality": [
        "08 软件质量管理",
        "软件质量",
        "质量",
        "cmmi",
        "spice",
        "iso",
    ],
    "new_progress": [
        "11 软件工程新进展",
        "软件工程新进展",
        "ai for se",
        "se for ai",
        "人工智能",
        "大模型",
    ],
    "french_exam": [
        "tcf",
        "tef",
        "compréhension",
        "vocabulaire",
        "grammaire",
        "français",
    ],
}


QUESTIONS = [
    {
        "id": "se01_process_model",
        "question": "某选课系统 5 月启动、9 月必须上线，团队没做过微服务。请设计合适的软件过程并说明理由。",
        "expected": "process",
    },
    {
        "id": "se02_rup_scrum",
        "question": "比较 RUP 和 Scrum 在迭代、角色、文档和风险控制上的差异。",
        "expected": "process",
    },
    {
        "id": "se03_requirements_validation",
        "question": "什么是需求可验证性？请举例说明如何把不可验证需求改写成可验证需求。",
        "expected": "requirements",
    },
    {
        "id": "se04_vision_stakeholders",
        "question": "Vision 文档中问题陈述和 stakeholder 分析分别解决什么问题？",
        "expected": "requirements",
    },
    {
        "id": "se05_use_case",
        "question": "在线考试系统中老师、学生、单点登录系统分别对应哪些用例？include 关系应该怎么表达？",
        "expected": "requirements",
    },
    {
        "id": "se06_architecture_coupling",
        "question": "解释高内聚低耦合，并说明它们如何影响软件设计质量。",
        "expected": "design",
    },
    {
        "id": "se07_microservice_risk",
        "question": "项目要求采用微服务但团队缺乏经验，这属于什么风险？如何应对？",
        "expected": "risk_management",
    },
    {
        "id": "se08_cocomo",
        "question": "软件项目估算中 COCOMO 模型关注哪些输入和输出？适合解决什么问题？",
        "expected": "estimation",
    },
    {
        "id": "se09_black_box",
        "question": "黑盒测试中等价类划分和边界值分析的思想分别是什么？",
        "expected": "testing",
    },
    {
        "id": "se10_white_box",
        "question": "白盒测试的语句覆盖、分支覆盖和路径覆盖有何区别？",
        "expected": "testing",
    },
    {
        "id": "se11_quality_model",
        "question": "软件质量属性中的可靠性、可维护性和可用性如何理解？",
        "expected": "quality",
    },
    {
        "id": "se12_cmmi_spice",
        "question": "CMMI 和 Automotive SPICE 属于什么类型的模型？它们用于解决什么管理问题？",
        "expected": "quality",
    },
    {
        "id": "se13_risk_management",
        "question": "风险识别、风险分析、风险应对和风险监控之间是什么关系？",
        "expected": "risk_management",
    },
    {
        "id": "se14_agile_xp",
        "question": "XP 极限编程有哪些典型实践？它和传统瀑布模型相比优势在哪里？",
        "expected": "process",
    },
    {
        "id": "se15_ai_for_se",
        "question": "AI for SE 和 SE for AI 分别是什么意思？请结合软件工程新进展说明。",
        "expected": "new_progress",
    },
]


@dataclass(frozen=True)
class Profile:
    id: str
    name: str
    asset: str


@dataclass(frozen=True)
class Chunk:
    id: str
    profile_id: str
    profile_name: str
    title: str
    source: str
    rank: int
    text: str


@dataclass(frozen=True)
class ScoredChunk:
    chunk: Chunk
    score: float


def load_local_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def load_chunks() -> tuple[list[Profile], list[Chunk]]:
    manifest = json.loads((ASSET_DIR / "manifest.json").read_text(encoding="utf-8"))
    profiles = [
        Profile(id=item["id"], name=item["name"], asset=item["asset"])
        for item in manifest["profiles"]
    ]
    chunks: list[Chunk] = []
    for profile in profiles:
        data = json.loads((ROOT / "phone-app" / "src" / "main" / "assets" / profile.asset).read_text(encoding="utf-8"))
        for item in data.get("chunks", []):
            text = item.get("text", "")
            if not text.strip():
                continue
            chunks.append(
                Chunk(
                    id=item["id"],
                    profile_id=profile.id,
                    profile_name=profile.name,
                    title=item.get("title", ""),
                    source=item.get("source", ""),
                    rank=int(item.get("rank", 0)),
                    text=text,
                )
            )
    return profiles, chunks


def tokenize(text: str) -> set[str]:
    lower = text.lower()
    words = {
        match.group(0)
        for match in re.finditer(r"[A-Za-zÀ-ÿ0-9_]{2,}", lower)
        if match.group(0) not in STOP_WORDS
    }
    cjk = "".join(ch for ch in lower if "\u4e00" <= ch <= "\u9fff")
    for index in range(max(0, len(cjk) - 1)):
        bigram = cjk[index : index + 2]
        if bigram not in STOP_WORDS:
            words.add(bigram)
    return set(list(words)[:MAX_QUERY_TERMS])


def extract_phrases(text: str) -> set[str]:
    normalized = re.sub(r"\s+", " ", text.lower()).strip()
    phrases: set[str] = set()
    for match in re.finditer(r"[\wÀ-ÿ][\wÀ-ÿ\s-]{5,60}", normalized):
        phrase = match.group(0).strip()
        if 6 <= len(phrase) and 2 <= len(re.split(r"\s+", phrase)) <= 8:
            phrases.add(phrase)
        if len(phrases) >= 6:
            break
    return phrases


def matched_concepts(query: str) -> set[str]:
    normalized = query.lower()
    concepts = set()
    for concept, aliases in CONCEPT_EXPANSIONS.items():
        for alias in aliases:
            if alias_matches(normalized, alias):
                concepts.add(concept)
                break
    return concepts


def alias_matches(normalized_text: str, alias: str) -> bool:
    normalized_alias = alias.lower()
    if not normalized_alias:
        return False
    if any("\u4e00" <= ch <= "\u9fff" for ch in normalized_alias):
        return normalized_alias in normalized_text
    return re.search(
        rf"(?<![A-Za-zÀ-ÿ0-9_]){re.escape(normalized_alias)}(?![A-Za-zÀ-ÿ0-9_])",
        normalized_text,
    ) is not None


def term_occurrences(normalized_text: str, term: str) -> int:
    if not term:
        return 0
    if any("\u4e00" <= ch <= "\u9fff" for ch in term):
        return normalized_text.count(term)
    return sum(
        1
        for _ in re.finditer(
            rf"(?<![A-Za-zÀ-ÿ0-9_]){re.escape(term)}(?![A-Za-zÀ-ÿ0-9_])",
            normalized_text,
        )
    )


def build_plan(query: str) -> tuple[set[str], set[str], set[str], set[str]]:
    base = tokenize(query)
    phrases = extract_phrases(query)
    concepts = matched_concepts(query)
    expanded = set(base)
    for concept in concepts:
        for alias in CONCEPT_EXPANSIONS.get(concept, []):
            expanded.update(tokenize(alias))
    return base, set(list(expanded)[:MAX_QUERY_TERMS]), phrases, concepts


def occurrences(text: str, needle: str) -> int:
    if not needle:
        return 0
    return text.count(needle)


def searchable(chunk: Chunk) -> str:
    return f"{chunk.title} {chunk.source} {chunk.text}".lower()


def lexical_score(chunk: Chunk, terms: set[str]) -> float:
    haystack = searchable(chunk)
    total = 0.0
    for term in terms:
        hits = term_occurrences(haystack, term)
        if hits == 0:
            continue
        title_boost = 3.0 if term_occurrences(chunk.title.lower(), term) else 1.0
        source_boost = 1.7 if term_occurrences(chunk.source.lower(), term) else 1.0
        length_norm = 1.0 / (1.0 + len(chunk.text) / 3200.0)
        idf_like = math.log(2.0 + min(len(term), 12))
        total += hits * title_boost * source_boost * length_norm * idf_like
    return total


def phrase_score(chunk: Chunk, phrases: set[str]) -> float:
    haystack = searchable(chunk)
    return sum(occurrences(haystack, phrase) * (8.0 + min(len(phrase), 30) / 3.0) for phrase in phrases)


def concept_score(chunk: Chunk, concepts: set[str]) -> float:
    haystack = searchable(chunk)
    total = 0.0
    for concept in concepts:
        for alias in CONCEPT_EXPANSIONS.get(concept, []):
            alias_terms = tokenize(alias)
            if not alias_terms:
                total += 3.0 if alias_matches(haystack, alias) else 0.0
            else:
                matched = sum(1 for term in alias_terms if term_occurrences(haystack, term))
                total += matched * 2.4
    return total


def header_text(chunk: Chunk) -> str:
    return f"{chunk.title} {chunk.source}".lower()


def topic_affinity_score(chunk: Chunk, concepts: set[str]) -> float:
    if not concepts:
        return 0.0
    header = header_text(chunk)
    body = chunk.text[:1400].lower()
    total = 0.0
    for concept in concepts:
        best = 0.0
        for marker in TOPIC_MARKERS.get(concept, []):
            if alias_matches(header, marker):
                best = max(best, 10.0)
            elif alias_matches(body, marker):
                best = max(best, 2.0)
        total += best
    return total


def topic_mismatch_penalty(chunk: Chunk, concepts: set[str]) -> float:
    if not concepts:
        return 0.0
    header = header_text(chunk)
    header_topics = {
        concept
        for concept, markers in TOPIC_MARKERS.items()
        if any(alias_matches(header, marker) for marker in markers)
    }
    if not header_topics:
        return 0.0
    unrelated = any(topic not in concepts for topic in header_topics)
    related = any(topic in concepts for topic in header_topics)
    if not unrelated:
        return 0.0
    return 2.0 if related else 28.0


def score_and_rank(chunks: list[Chunk], scorer) -> list[Chunk]:
    scored = [ScoredChunk(chunk, scorer(chunk)) for chunk in chunks]
    return [
        item.chunk
        for item in sorted(scored, key=lambda item: (-item.score, item.chunk.rank))
        if item.score > 0
    ]


def similarity(left: Chunk, right: Chunk) -> float:
    left_terms = tokenize(f"{left.title} {left.text[:900]}")
    right_terms = tokenize(f"{right.title} {right.text[:900]}")
    if not left_terms or not right_terms:
        return 0.0
    intersection = len(left_terms & right_terms)
    union = len(left_terms | right_terms)
    return intersection / union if union else 0.0


def retrieve(question: str, chunks: list[Chunk]) -> list[ScoredChunk]:
    base, expanded, phrases, concepts = build_plan(question)
    fused: dict[str, float] = {}
    by_id = {chunk.id: chunk for chunk in chunks}

    def add_ranking(ranking: list[Chunk], weight: float) -> None:
        for index, chunk in enumerate(ranking):
            fused[chunk.id] = fused.get(chunk.id, 0.0) + weight / (RRF_K + index + 1.0)

    add_ranking(score_and_rank(chunks, lambda chunk: lexical_score(chunk, base)), 1.0)
    add_ranking(score_and_rank(chunks, lambda chunk: lexical_score(chunk, expanded)), 0.82)
    add_ranking(score_and_rank(chunks, lambda chunk: phrase_score(chunk, phrases)), 0.7)
    add_ranking(score_and_rank(chunks, lambda chunk: concept_score(chunk, concepts)), 0.62)
    add_ranking(score_and_rank(chunks, lambda chunk: topic_affinity_score(chunk, concepts)), 1.15)

    ranked = [
        ScoredChunk(
            by_id[chunk_id],
            score
            + (lexical_score(by_id[chunk_id], expanded) + phrase_score(by_id[chunk_id], phrases)) / 1200.0
            + topic_affinity_score(by_id[chunk_id], concepts) / 100.0
            - topic_mismatch_penalty(by_id[chunk_id], concepts) / 100.0,
        )
        for chunk_id, score in fused.items()
        if chunk_id in by_id
    ]
    ranked.sort(key=lambda item: (-item.score, item.chunk.rank))

    selected: list[ScoredChunk] = []
    total_chars = 0
    candidates = ranked[:]
    while candidates and len(selected) < MAX_CONTEXT_CHUNKS and total_chars < MAX_CONTEXT_CHARS:
        next_item = max(
            candidates,
            key=lambda item: (
                MMR_RELEVANCE_WEIGHT * item.score
                - (1.0 - MMR_RELEVANCE_WEIGHT) * max((similarity(item.chunk, chosen.chunk) for chosen in selected), default=0.0)
                - (0.08 if any(chosen.chunk.source == item.chunk.source for chosen in selected) else 0.0)
            ),
        )
        candidates.remove(next_item)
        if len(next_item.chunk.text) < 80:
            continue
        selected.append(next_item)
        total_chars += len(next_item.chunk.text)
    return selected


def compact_context(chunks: list[ScoredChunk]) -> str:
    parts = []
    total = 0
    for item in chunks:
        chunk = item.chunk
        header = f"[{chunk.profile_name} | {chunk.title} | {chunk.source}]"
        text = chunk.text[:2200]
        block = f"{header}\n{text}"
        if total + len(block) > MAX_CONTEXT_CHARS:
            break
        parts.append(block)
        total += len(block)
    return "\n\n".join(parts)


def configured_providers(config: dict[str, str]) -> list[dict[str, str]]:
    providers = [
        {
            "name": "primary",
            "url": config.get("CODEX_RELAY_URL", ""),
            "api_key": config.get("CODEX_RELAY_API_KEY", ""),
            "model": config.get("CODEX_RELAY_MODEL", "gpt-5.5"),
        },
        {
            "name": "fallback",
            "url": config.get("CODEX_RELAY_FALLBACK_URL", ""),
            "api_key": config.get("CODEX_RELAY_FALLBACK_API_KEY", ""),
            "model": config.get("CODEX_RELAY_FALLBACK_MODEL", config.get("CODEX_RELAY_MODEL", "gpt-5.5")),
        },
    ]
    return [
        provider
        for provider in providers
        if provider["url"].strip() and provider["api_key"].strip()
    ]


def request_relay(config: dict[str, str], prompt: str, timeout: int) -> str:
    providers = configured_providers(config)
    if not providers:
        raise RuntimeError("no relay providers configured")

    failures: list[str] = []
    for provider in providers:
        try:
            return request_provider(provider, prompt, timeout)
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{provider['name']}: {exc}")
    raise RuntimeError("; ".join(failures))


def request_provider(provider: dict[str, str], prompt: str, timeout: int) -> str:
    url = provider["url"].rstrip("/") + "/v1/responses"
    body = {
        "model": provider["model"],
        "input": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": prompt,
                    }
                ],
            }
        ],
        "max_output_tokens": 500,
        "reasoning": {"effort": "minimal"},
        "text": {"verbosity": "low"},
    }
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": "Bearer " + provider["api_key"],
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="ignore")[:500]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc

    if payload.get("output_text"):
        return payload["output_text"].strip()
    parts: list[str] = []
    for item in payload.get("output", []):
        for block in item.get("content", []):
            text = block.get("text")
            if text:
                parts.append(text.strip())
    return "\n".join(parts).strip()


def parse_jsonish(text: str) -> dict:
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```(?:json)?", "", stripped).strip()
        stripped = re.sub(r"```$", "", stripped).strip()
    start = stripped.find("{")
    end = stripped.rfind("}")
    if start >= 0 and end > start:
        stripped = stripped[start : end + 1]
    return json.loads(stripped)


def evaluate_question(config: dict[str, str], question: dict, chunks: list[Chunk], timeout: int) -> dict:
    selected = retrieve(question["question"], chunks)
    context = compact_context(selected)
    prompt = f"""
你是上海交通大学软件工程课程助教。请基于下面的知识库片段回答期末考试题，并判断这些片段是否匹配题目的背景。

要求：
- 只输出 JSON，不要 Markdown。
- answer_cn 用 3-6 个要点精炼回答。
- background_match_score 是 1-5 的整数，5 表示片段非常贴合题目背景。
- matched_concepts 写 1-3 个中文短语。

题目：
{question["question"]}

知识库片段：
{context}

输出 JSON schema:
{{"answer_cn": "...", "background_match_score": 5, "matched_concepts": ["..."], "context_sufficient": true}}
""".strip()
    started = time.time()
    raw = request_relay(config, prompt, timeout=timeout)
    elapsed_ms = int((time.time() - started) * 1000)
    parsed = parse_jsonish(raw)
    return {
        "id": question["id"],
        "question": question["question"],
        "expected": question["expected"],
        "elapsedMs": elapsed_ms,
        "retrieved": [
            {
                "profile": item.chunk.profile_name,
                "title": item.chunk.title,
                "source": item.chunk.source,
                "score": round(item.score, 6),
            }
            for item in selected
        ],
        "model": parsed,
        "raw": raw,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=15)
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()

    config = load_local_properties(ROOT / "local.properties")
    required = ["CODEX_RELAY_URL", "CODEX_RELAY_API_KEY", "CODEX_RELAY_MODEL"]
    missing = [key for key in required if not config.get(key)]
    if missing:
        print(f"missing local.properties keys: {', '.join(missing)}", file=sys.stderr)
        return 2

    _, all_chunks = load_chunks()
    software_chunks = [chunk for chunk in all_chunks if chunk.profile_id == "software_engineering"]
    questions = QUESTIONS[: args.limit]
    if len(questions) != args.limit:
        print(f"only {len(questions)} questions available", file=sys.stderr)
        return 2

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    results = []
    for index, question in enumerate(questions, start=1):
        print(f"[{index:02d}/{len(questions)}] {question['id']}: {question['question']}")
        result = evaluate_question(config, question, software_chunks, timeout=args.timeout)
        score = result["model"].get("background_match_score")
        sufficient = result["model"].get("context_sufficient")
        top = result["retrieved"][0] if result["retrieved"] else {}
        print(f"  match={score} sufficient={sufficient} top={top.get('title')} / {top.get('source')}")
        results.append(result)

    summary = {
        "total": len(results),
        "averageBackgroundMatch": round(
            sum(int(item["model"].get("background_match_score", 0)) for item in results) / len(results),
            3,
        ),
        "sufficientCount": sum(1 for item in results if item["model"].get("context_sufficient") is True),
        "results": results,
    }
    output_path = REPORT_DIR / "software_kb_ai_eval.json"
    output_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nwrote {output_path}")
    print(
        f"averageBackgroundMatch={summary['averageBackgroundMatch']} "
        f"sufficient={summary['sufficientCount']}/{summary['total']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
