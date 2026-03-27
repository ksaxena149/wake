# WAKE Server

## kiwix-serve Setup

### Installation

Download and install the kiwix-tools binary (v3.7.0):

```bash
wget https://download.kiwix.org/release/kiwix-tools/kiwix-tools_linux-x86_64-3.7.0-1.tar.gz
tar -xzf kiwix-tools_linux-x86_64-3.7.0-1.tar.gz
sudo mv kiwix-tools_linux-x86_64-3.7.0-1/kiwix-serve /usr/local/bin/
kiwix-serve --version
```

Note: the extracted directory is named `kiwix-tools_linux-x86_64-3.7.0-1/` (with version suffix), not `kiwix-tools_linux-x86_64/`.

### ZIM File

ZIM files live in `~/zim/` (outside the repo — not committed).

Download the English Wikipedia Top Mini ZIM (~315 MB):

```bash
mkdir -p ~/zim
wget -P ~/zim "https://download.kiwix.org/zim/wikipedia/wikipedia_en_top_mini_2026-03.zim"
```

ZIM used: `wikipedia_en_top_mini_2026-03.zim`

Avoid `wikipedia_en_simple_all_mini` — the 2025-11 build is 434 MB and the 2024-01 build returns 404. The `top_mini` variant is smaller and loads correctly.

### Running

```bash
kiwix-serve --port 8888 ~/zim/wikipedia_en_top_mini_2026-03.zim
```

Server listens on `http://localhost:8888` (also accessible on LAN at the machine's IP).

### Verified Search API

```
http://localhost:8888/search?lang=&pattern=water
```

Returns HTML search results. This is the endpoint the WAKE FastAPI daemon proxies.

> The kiwix UI search bar (`#lang=...`) is broken — do not use it. The `/search` API works correctly.
