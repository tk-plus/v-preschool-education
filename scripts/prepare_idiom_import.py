"""将 chinese-xinhua 的 idiom.json 转成可重复执行的 MySQL 导入脚本。"""

import argparse
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path
from typing import TextIO


ROOT = Path(__file__).resolve().parents[1]
SOURCE = "chinese-xinhua"
BATCH_SIZE = 500
PINYIN_SYLLABLE = re.compile(r"[a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ]+")
DIFFICULTY_SQL = (
    ROOT / "java/src/main/resources/db/migration/V8__initial_idiom_difficulty.sql"
)


def mysql_text(value: str) -> str:
    """按 UTF-8 字节写入字符串，避免引号和反斜杠改变 SQL 的含义。"""
    return "CONVERT(X'" + value.encode("utf-8").hex() + "' USING utf8mb4)"


def is_han(char: str) -> bool:
    return unicodedata.name(char, "").startswith("CJK UNIFIED IDEOGRAPH-")


def collect_entries(rows: list[dict]) -> dict[str, tuple[str, bool, bool]]:
    entries = {}
    for index, row in enumerate(rows, start=1):
        term = unicodedata.normalize("NFC", row["word"].strip())
        pinyin = unicodedata.normalize("NFC", (row.get("pinyin") or "").strip())
        if not term or len(term) > 64 or len(pinyin) > 255:
            raise ValueError(f"第 {index} 条记录的词面或拼音超出字段范围")

        is_four = len(term) == 4 and all(map(is_han, term))
        syllables = pinyin.lower().split()
        format_ok = is_four and len(syllables) == 4 and all(
            PINYIN_SYLLABLE.fullmatch(syllable) for syllable in syllables
        )
        previous = entries.get(term)
        if previous is None or (format_ok and not previous[2]):
            entries[term] = (pinyin, is_four, format_ok)
    return entries


def estimate_difficulties(terms: list[str]) -> dict[int, list[str]]:
    """用词库内的汉字覆盖数估算认字难度，每个字在一条词里只计一次。"""
    character_counts = Counter(char for term in terms for char in set(term) if is_han(char))
    groups: dict[int, list[str]] = {level: [] for level in range(1, 5)}
    for term in terms:
        counts = sorted(character_counts[char] for char in set(term) if is_han(char))
        rarest = counts[0] if counts else 0
        second_rarest = counts[1] if len(counts) > 1 else rarest

        if len(term) >= 8 or rarest <= 4:
            level = 4
        elif len(term) >= 5 or rarest <= 20 or second_rarest <= 40:
            level = 3
        elif rarest < 80 or second_rarest < 160:
            level = 2
        else:
            level = 1
        groups[level].append(term)
    return groups


def write_difficulty_updates(sql: TextIO, groups: dict[int, list[str]]) -> None:
    # 已发布成语的人工分级优先，之后只填仍然为空的词条。
    difficulty_sql = DIFFICULTY_SQL.read_text(encoding="utf-8")
    sql.write(difficulty_sql.rstrip() + "\n\n")
    for level, terms in groups.items():
        for offset in range(0, len(terms), BATCH_SIZE):
            batch = terms[offset : offset + BATCH_SIZE]
            values = ",\n".join(f"    {mysql_text(term)}" for term in batch)
            sql.write(
                f"UPDATE idiom_entry SET difficulty = {level}\n"
                "WHERE difficulty IS NULL AND term IN (\n"
                f"{values}\n);\n"
            )


def write_sql(
    output: Path, entries: dict[str, tuple[str, bool, bool]], difficulty_only: bool
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    items = [] if difficulty_only else list(entries.items())
    groups = estimate_difficulties(list(entries))
    source = mysql_text(SOURCE)
    with output.open("w", encoding="utf-8", newline="\n") as sql:
        sql.write("-- Apply after Flyway V6 in the development database.\nSET NAMES utf8mb4;\nSTART TRANSACTION;\n")
        for offset in range(0, len(items), BATCH_SIZE):
            values = []
            for term, (pinyin, is_four, format_ok) in items[offset : offset + BATCH_SIZE]:
                pinyin_sql = mysql_text(pinyin) if pinyin else "NULL"
                values.append(
                    f"    ({mysql_text(term)}, {pinyin_sql}, {source}, {int(is_four)}, {int(format_ok)})"
                )
            sql.write(
                "INSERT INTO idiom_entry "
                "(term, source_pinyin, source_name, is_four_character, pinyin_format_ok) VALUES\n"
            )
            sql.write(",\n".join(values) + "\nON DUPLICATE KEY UPDATE id = id;\n")
        write_difficulty_updates(sql, groups)
        sql.write("COMMIT;\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "docs" / "idiom.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--difficulty-only", action="store_true", help="只补词条的空难度")
    args = parser.parse_args()
    if args.output is None:
        filename = "idiom-difficulty.sql" if args.difficulty_only else "idiom-import.sql"
        args.output = ROOT / "java" / "target" / filename
    if args.input.resolve() == args.output.resolve():
        parser.error("输入和输出必须使用不同文件")

    rows = json.loads(args.input.read_text(encoding="utf-8-sig"))
    entries = collect_entries(rows)
    four = sum(entry[1] for entry in entries.values())
    valid_pinyin = sum(entry[2] for entry in entries.values())
    write_sql(args.output, entries, args.difficulty_only)
    print(f"词条 {len(entries)} 条，其中四字 {four} 条，拼音格式通过 {valid_pinyin} 条。")
    print(f"SQL: {args.output}")


if __name__ == "__main__":
    main()
