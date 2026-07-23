from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .artifacts import create_run_directory
from .browse import BrowseConfig, BrowseRunner
from .device import build_report, connect
from .recovery import SafeRecovery
from .state import inspect_device_state
from .tiktok import TikTokController


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Android TikTok RPA")
    parser.add_argument("--serial", help="ADB device serial; optional for one device")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("doctor", help="check device and TikTok installation")
    subparsers.add_parser("launch", help="launch TikTok without interaction")
    state_parser = subparsers.add_parser(
        "state",
        help="inspect current TikTok page and blockers",
    )
    state_parser.add_argument(
        "--expected-page",
        choices=("feed", "comments", "search_input", "search_results", "inbox"),
        default="feed",
    )
    state_parser.add_argument(
        "--recover",
        action="store_true",
        help="apply only known reversible recovery actions",
    )
    snapshot_parser = subparsers.add_parser("snapshot", help="save screen and UI hierarchy")
    snapshot_parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("runtime"),
        help="directory for runtime artifacts",
    )
    search_parser = subparsers.add_parser("search", help="search a keyword in TikTok")
    search_parser.add_argument("keyword", help="keyword to search")
    search_parser.add_argument("--wait", type=float, default=4.0)
    search_parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("runtime"),
    )
    browse_parser = subparsers.add_parser("browse", help="browse videos and read comments")
    browse_parser.add_argument("--count", type=int, default=3, help="number of videos")
    browse_parser.add_argument("--watch-min", type=float, default=7.0)
    browse_parser.add_argument("--watch-max", type=float, default=15.0)
    browse_parser.add_argument("--comment-min", type=float, default=2.0)
    browse_parser.add_argument("--comment-max", type=float, default=6.0)
    browse_parser.add_argument("--comment-scrolls", type=int, default=1)
    browse_parser.add_argument("--comment-pause-min", type=float, default=0.8)
    browse_parser.add_argument("--comment-pause-max", type=float, default=1.8)
    browse_parser.add_argument("--comment-swipe-min", type=float, default=0.28)
    browse_parser.add_argument("--comment-swipe-max", type=float, default=0.9)
    browse_parser.add_argument("--reply-threads", type=int, default=1)
    browse_parser.add_argument("--swipe-min", type=float, default=0.18)
    browse_parser.add_argument("--swipe-max", type=float, default=0.95)
    browse_parser.add_argument("--between-min", type=float, default=1.5)
    browse_parser.add_argument("--between-max", type=float, default=3.5)
    browse_parser.add_argument("--session-minutes", type=float, default=20.0)
    browse_parser.add_argument(
        "--open-comments",
        action=argparse.BooleanOptionalAction,
        default=True,
    )
    browse_parser.add_argument(
        "--interest-keyword",
        action="append",
        default=[],
        help="repeat for multiple keywords; empty means every video is relevant",
    )
    browse_parser.add_argument(
        "--apply-not-interested",
        action="store_true",
        help="really click Not interested for irrelevant videos",
    )
    browse_parser.add_argument(
        "--like-rate",
        type=float,
        default=0.0,
        help="like probability per relevant video, from 0 to 1",
    )
    browse_parser.add_argument(
        "--favorite-rate",
        type=float,
        default=0.0,
        help="favorite probability per relevant video, from 0 to 1",
    )
    browse_parser.add_argument(
        "--comment-rate",
        type=float,
        default=0.0,
        help="comment probability per relevant video, from 0 to 1",
    )
    browse_parser.add_argument(
        "--comment-text",
        action="append",
        default=[],
        help="comment candidate; repeat to provide multiple candidates",
    )
    browse_parser.add_argument("--max-likes", type=int, default=3)
    browse_parser.add_argument("--max-favorites", type=int, default=2)
    browse_parser.add_argument("--max-comments", type=int, default=1)
    browse_parser.add_argument(
        "--apply-interactions",
        action="store_true",
        help="really submit selected likes, favorites and comments",
    )
    browse_parser.add_argument(
        "--auto-recover",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="recover known reversible popups and accidental pages",
    )
    browse_parser.add_argument("--max-recovery-actions", type=int, default=3)
    browse_parser.add_argument(
        "--output-root",
        type=Path,
        default=Path("runtime"),
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        device = connect(args.serial)

        if args.command == "doctor":
            print(json.dumps(build_report(device).to_dict(), ensure_ascii=False, indent=2))
            return

        if args.command == "state":
            result = (
                SafeRecovery(device).recover(args.expected_page).to_dict()
                if args.recover
                else inspect_device_state(device).to_dict()
            )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return

        controller = TikTokController(device)
        if args.command == "launch":
            print(json.dumps(controller.launch(), ensure_ascii=False, indent=2))
            return

        if args.command == "snapshot":
            run_directory = create_run_directory(args.output_root)
            screenshot_path, hierarchy_path = controller.snapshot(run_directory)
            print(json.dumps({
                "run_directory": str(run_directory.resolve()),
                "screenshot": str(screenshot_path.resolve()),
                "hierarchy": str(hierarchy_path.resolve()),
            }, ensure_ascii=False, indent=2))
            return

        if args.command == "search":
            result = controller.search(args.keyword, wait_seconds=args.wait)
            run_directory = create_run_directory(args.output_root)
            screenshot_path, hierarchy_path = controller.snapshot(run_directory)
            print(json.dumps({
                "run_directory": str(run_directory.resolve()),
                "screenshot": str(screenshot_path.resolve()),
                "hierarchy": str(hierarchy_path.resolve()),
                **result.to_dict(),
            }, ensure_ascii=False, indent=2))
            return

        if args.command == "browse":
            config = BrowseConfig(
                video_count=args.count,
                watch_seconds_min=args.watch_min,
                watch_seconds_max=args.watch_max,
                open_comments=args.open_comments,
                comment_seconds_min=args.comment_min,
                comment_seconds_max=args.comment_max,
                comment_scrolls=args.comment_scrolls,
                comment_scroll_pause_min=args.comment_pause_min,
                comment_scroll_pause_max=args.comment_pause_max,
                comment_swipe_seconds_min=args.comment_swipe_min,
                comment_swipe_seconds_max=args.comment_swipe_max,
                max_reply_threads=args.reply_threads,
                video_swipe_seconds_min=args.swipe_min,
                video_swipe_seconds_max=args.swipe_max,
                between_video_pause_min=args.between_min,
                between_video_pause_max=args.between_max,
                max_session_minutes=args.session_minutes,
                interest_keywords=tuple(args.interest_keyword),
                apply_not_interested=args.apply_not_interested,
                like_probability=args.like_rate,
                favorite_probability=args.favorite_rate,
                comment_probability=args.comment_rate,
                comment_texts=tuple(args.comment_text),
                apply_interactions=args.apply_interactions,
                max_likes=args.max_likes,
                max_favorites=args.max_favorites,
                max_comments=args.max_comments,
                auto_recover_interruptions=args.auto_recover,
                max_recovery_actions=args.max_recovery_actions,
            )
            run_directory, records = BrowseRunner(device, args.output_root).run(config)
            print(json.dumps({
                "run_directory": str(run_directory.resolve()),
                "videos": [record.to_dict() for record in records],
            }, ensure_ascii=False, indent=2))
    except Exception as error:
        print(f"错误：{type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
