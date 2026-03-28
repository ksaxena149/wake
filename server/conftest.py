import sys
from pathlib import Path

# Ensure server/ is on sys.path so test files can import local modules directly.
sys.path.insert(0, str(Path(__file__).parent))
