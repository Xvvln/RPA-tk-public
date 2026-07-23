from __future__ import annotations

from datetime import datetime
from pathlib import Path


def create_run_directory(root: Path) -> Path:
    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    run_directory = root / timestamp
    run_directory.mkdir(parents=True, exist_ok=False)
    return run_directory

