"""Sprint 7 DuckDB ETL trigger plus analytics dashboard launcher.

Run a full ETL pass once (--once) or in a refresh loop, and make sure the
Flask analytics dashboard (sprint6/front-end) is listening on FRONTEND_PORT.

Configuration (all optional, from executor/.env or the environment):
  DUCKDB_PATH                    DuckDB file (default: <repo>/analytics.duckdb)
  ETL_REFRESH_INTERVAL_SECONDS   seconds between incremental passes (default 45)
  FRONTEND_PORT                  dashboard port the Flask app listens on (default 5000)
  DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD
                                 Postgres connection (POSTGRES_* also works)
"""

import argparse
import logging
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

from analytics_pipeline import build_pipeline
from dotenv import load_dotenv

EXECUTOR_DIR = Path(__file__).resolve().parent
REPO_ROOT = EXECUTOR_DIR.parent
FRONTEND_DIR = REPO_ROOT / "sprint6" / "front-end"
LOCK_FILE = EXECUTOR_DIR / ".etl_trigger.lock"

LOGGER = logging.getLogger("etl_trigger")


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S",
        stream=sys.stdout,
    )


def _get_env(*names: str, default: str) -> str:
    for name in names:
        value = os.getenv(name)
        if value not in (None, ""):
            return value
    return default


def _load_env() -> None:
    for candidate in (EXECUTOR_DIR / ".env", REPO_ROOT / ".env"):
        if candidate.is_file():
            load_dotenv(candidate, override=True)


def _port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        try:
            sock.connect(("127.0.0.1", port))
            return True
        except OSError:
            return False


def _abs(path: str) -> str:
    return str(Path(path).expanduser().resolve())


def ensure_dashboard() -> None:
    """Start the Flask analytics dashboard if it is not already listening."""
    if not FRONTEND_DIR.is_dir():
        LOGGER.warning("Frontend dir '%s' not found; skipping dashboard", FRONTEND_DIR)
        return

    port = int(_get_env("FRONTEND_PORT", default="5000"))
    if _port_in_use(port):
        LOGGER.info("Dashboard already listening on port %s; not starting another", port)
        return

    duckdb_path = os.getenv("DUCKDB_PATH", str(REPO_ROOT / "analytics.duckdb"))

    env = dict(os.environ)
    env["DUCKDB_PATH"] = _abs(duckdb_path)
    env["FRONTEND_PORT"] = str(port)

    log_file = FRONTEND_DIR / "analytics_dashboard.log"
    with open(log_file, "a", encoding="utf-8") as out:
        process = subprocess.Popen(
            [sys.executable, "app.py"],
            cwd=str(FRONTEND_DIR),
            env=env,
            stdout=out,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
        )
    LOGGER.info("Started analytics dashboard (pid %s, %s) -> %s", process.pid, str(FRONTEND_DIR / "app.py"), log_file)


def run_once() -> None:
    """Run a full, incremental ETL pass against DuckDB."""
    pipeline = build_pipeline()
    pipeline.initialize()
    pipeline.run_dim_date()
    pipeline.run_dimensions()
    result = pipeline.run_facts()

    LOGGER.info(
        "ETL pass complete: extracted=%d inserted=%d dead_lettered=%d",
        result.extracted,
        result.inserted,
        result.dead_lettered,
    )


def _pid_alive(pid: int) -> bool:
    if sys.platform == "win32":
        try:
            probe = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/NH"],
                capture_output=True,
                text=True,
                creationflags=0x08000000,  # CREATE_NO_WINDOW
            )
        except OSError:
            return False
        return str(pid) in probe.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _is_running() -> bool:
    if not LOCK_FILE.exists():
        return False
    try:
        pid = int(LOCK_FILE.read_text(encoding="utf-8").strip())
    except (ValueError, OSError):
        return True
    return _pid_alive(pid)


def _acquire_lock() -> None:
    LOCK_FILE.write_text(str(os.getpid()), encoding="utf-8")


def _release_lock() -> None:
    try:
        LOCK_FILE.unlink()
    except OSError:
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description="Sprint 7 DuckDB ETL trigger + dashboard launcher")
    parser.add_argument("--once", action="store_true", help="Run a single ETL pass, then exit")
    args = parser.parse_args()

    _configure_logging()
    _load_env()

    LOGGER.info("DuckDB ETL trigger starting (repo=%s)", REPO_ROOT)

    # The dashboard should be reachable even when another instance owns the ETL loop.
    ensure_dashboard()

    if _is_running():
        LOGGER.info("Another ETL trigger is already running (%s); skipping ETL loop", LOCK_FILE)
        return

    _acquire_lock()
    refresh_seconds = int(_get_env("ETL_REFRESH_INTERVAL_SECONDS", "DUCKDB_REFRESH_INTERVAL_SECONDS", default="45"))

    try:
        if args.once:
            run_once()
            return

        LOGGER.info("ETL refresh loop active every %s seconds", refresh_seconds)
        while True:
            try:
                run_once()
            except Exception:
                LOGGER.exception("ETL pass failed")
            time.sleep(refresh_seconds)
    finally:
        _release_lock()


if __name__ == "__main__":
    main()