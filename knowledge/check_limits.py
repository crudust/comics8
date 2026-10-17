from pathlib import Path

REVIEW_THRESHOLD = 10_000
KNOWLEDGE_ROOT = Path(__file__).resolve().parent


def main() -> int:
    files: list[tuple[int, str, Path]] = []

    for path in sorted(KNOWLEDGE_ROOT.rglob("*.md")):
        character_count = len(path.read_text(encoding="utf-8"))
        relative_path = path.relative_to(KNOWLEDGE_ROOT)
        level = "REVIEW" if character_count > REVIEW_THRESHOLD else "OK"
        files.append((character_count, level, relative_path))

    for character_count, level, relative_path in files:
        print(f"{level:6} {character_count:6} {relative_path}")

    review_count = sum(1 for _, level, _ in files if level == "REVIEW")
    if review_count:
        print(
            f"\n{review_count} file(s) exceed {REVIEW_THRESHOLD:,} chars "
            "(consider trivia pruning or splitting if multiple distinct lifecycles exist)."
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
