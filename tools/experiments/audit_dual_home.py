"""Read-only device check: each running Duo content display owns one real HOME task.

Run while the fixed dual session is active. Does not launch apps or change settings.
This validates task placement only, not rendered animations or system panel support.
"""
import argparse
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()

    def shell(*command):
        return subprocess.check_output(
            [args.adb, "shell", *command], text=True, encoding="utf-8", errors="replace"
        )

    status = shell("content", "call", "--uri",
                   "content://io.github.sixzleo.tabfold.projection.surface",
                   "--method", "fixed-dual-session")
    ids = re.search(r"status=running .*content=(\d+),(\d+)", status)
    if not ids:
        raise SystemExit("FAIL: no running fixed dual content pair")
    activity_dump = shell("dumpsys", "activity", "activities")
    sections = list(re.finditer(
        r"^Display #(\d+) \(activities from top to bottom\):", activity_dump, re.M
    ))
    blocks = {}
    for index, match in enumerate(sections):
        end = sections[index + 1].start() if index + 1 < len(sections) else len(activity_dump)
        blocks[match.group(1)] = activity_dump[match.end():end]
    for display_id in ids.groups():
        block = blocks.get(display_id, "")
        task_header = ""
        homes = []
        for line in block.splitlines():
            if re.search(r"\* Task\{", line):
                task_header = line
            if re.search(r"\* Hist\s+#\d+: ActivityRecord\{.*\.DuoSecondaryActivity\s+t\d+", line):
                if "type=home" not in task_header:
                    raise SystemExit(f"FAIL: display {display_id} Duo activity is not in a HOME task")
                homes.append(re.search(r"\bt(\d+)\}", line).group(1))
        if len(homes) != 1:
            raise SystemExit(f"FAIL: display {display_id} has {len(homes)} Duo HOME instances")
        if "com.miui.home/.launcher.SecondaryDisplayLauncher" in block:
            raise SystemExit(f"FAIL: display {display_id} still contains the Xiaomi secondary launcher")
        print(f"PASS display={display_id} HOME task={homes[0]} one Duo instance; no Xiaomi fallback")


if __name__ == "__main__":
    main()
