"""
TurboDrop Unified Logging Subsystem
Logs all network operations, client connections, transfers, failovers, and exceptions
to a persistent rotating file (logs/pc_server.log) and console with full stack traces.
"""

import os
import sys
import logging
from logging.handlers import RotatingFileHandler

if getattr(sys, 'frozen', False):
    base_dir = os.path.dirname(sys.executable)
else:
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

LOG_DIR = os.path.abspath(os.path.join(base_dir, "logs"))
os.makedirs(LOG_DIR, exist_ok=True)
LOG_FILE = os.path.join(LOG_DIR, "pc_server.log")

# Create logger
logger = logging.getLogger("TurboDrop")
logger.setLevel(logging.DEBUG)

if not logger.handlers:
    # 1. File Handler (Rotating, max 5MB, 3 backups)
    file_fmt = logging.Formatter("[%(asctime)s] [%(levelname)s] [%(threadName)s] %(message)s", datefmt="%Y-%m-%d %H:%M:%S")
    fh = RotatingFileHandler(LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
    fh.setLevel(logging.DEBUG)
    fh.setFormatter(file_fmt)
    logger.addHandler(fh)

    # 2. Console Handler
    cons_fmt = logging.Formatter("[%(asctime)s] [%(levelname)s] %(message)s", datefmt="%H:%M:%S")
    ch = logging.StreamHandler(sys.stdout)
    ch.setLevel(logging.INFO)
    ch.setFormatter(cons_fmt)
    logger.addHandler(ch)

def log_info(msg: str):
    logger.info(msg)

def log_warn(msg: str):
    logger.warning(msg)

def log_error(msg: str, exc: Exception = None):
    if exc:
        logger.error(f"{msg}: {exc}", exc_info=True)
    else:
        logger.error(msg)

def log_debug(msg: str):
    logger.debug(msg)
