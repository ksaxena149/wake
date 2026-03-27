---
name: WAKE Master Plan
overview: A single master plan combining the WAKE technical roadmap (7 phases, ~14 weeks) with a full project management workflow — GitHub as the source of truth, a rigid human+AI collaboration loop, and concrete setup steps before a single line of code is written.
todos:
  - id: phase-1
    content: "Phase -1: GitHub Student Pack, repo creation, Git init, monorepo structure, labels, milestones, Projects board, Issue templates, Notion workspace, ADRs"
    status: pending
  - id: phase0
    content: "Phase 0: kiwix-serve running locally, ZIM verified, Android Studio blank build succeeds"
    status: pending
  - id: phase1
    content: "Phase 1: Python WAKE Gateway Daemon — FastAPI + SQLite + chunker + PyNaCl signing + pytest passing"
    status: pending
  - id: phase2
    content: "Phase 2: Android app — Foreground Service, Room DB, OkHttp client, WebView renderer, end-to-end over local WiFi"
    status: pending
  - id: phase3
    content: "Phase 3: Nearby Connections API — peer discovery, manifest exchange, file transfer, epidemic routing"
    status: pending
  - id: phase4
    content: "Phase 4: PRoPHET routing — routing_table in Room, ProphetRouter.kt, forwarding decision, TTL enforcement"
    status: pending
  - id: phase5
    content: "Phase 5: Security — Android Keystore identity, request signing, relay signature verification"
    status: pending
  - id: phase6
    content: "Phase 6: Pi deployment — systemd services, WiFi hotspot, field test sequence"
    status: pending
isProject: false
---

# WAKE — Master Project Plan

## Roles

- **You (PM + Coder)**: create issues, make architecture decisions, write ADRs, review AI output, commit, merge, test
- **AI (Cursor)**: implement issues, write tests, follow conventions, never decides architecture alone

The AI executes. You decide.

---

## Phase -1 — Project Infrastructure Setup

**Duration:** 1–2 days | **Hardware:** Laptop only | **Goal:** Everything is in place before a single line of code is written

This phase has no coding. It is pure setup. Complete every step in order. Do not skip steps.

---

### Step 1 — Apply for GitHub Student Developer Pack

The Student Pack gives you GitHub Pro for free, which unlocks private repos with full features, more CI minutes, and other tools used in this project.

1. Go to [education.github.com/pack](https://education.github.com/pack)
2. Click **"Get student benefits"**
3. Sign in with your existing GitHub account
4. When asked to verify, select your school and use your student/university email address
5. Upload proof of enrollment if prompted (student ID photo or enrollment letter)
6. Submit — approval can take a few hours to a few days

You can continue with the rest of this phase while waiting for approval.

---

### Step 2 — Create the GitHub Repository

1. Go to [github.com/new](https://github.com/new)
2. Fill in:
  - **Repository name:** `wake`
  - **Description:** `Wireless Asynchronous Knowledge Exchange — Android DTN Mesh`
  - **Visibility:** Private
  - Check **"Add a README file"**
  - Leave everything else as default
3. Click **"Create repository"**

You now have a remote repository at `https://github.com/YOUR_USERNAME/wake`.

---

### Step 3 — Verify Your SSH Key is Connected to GitHub

This step makes sure your machine can push/pull to GitHub without typing a password every time.

1. Open a terminal and run:

```bash
   ssh -T git@github.com
   

```

1. If you see `Hi YOUR_USERNAME! You've successfully authenticated` — you are done, skip to Step 4.
2. If you see `Permission denied (publickey)`:
  - Run `cat ~/.ssh/id_ed25519.pub` (or `id_rsa.pub` if that file does not exist)
  - If neither file exists, run `ssh-keygen -t ed25519 -C "your@email.com"` and press Enter through all prompts
  - Copy the full output of the `cat` command
  - Go to [github.com/settings/keys](https://github.com/settings/keys), click **"New SSH key"**, paste it in, click **"Add SSH key"**
  - Run `ssh -T git@github.com` again to confirm it works

---

### Step 4 — Clone the Repo and Build the Folder Structure

Your workspace already exists at `/home/kush/code/wake/` but it is not a Git repository yet. Clone the GitHub repo and set it up.

1. Open a terminal and run:

```bash
   cd /home/kush/code
   git clone git@github.com:YOUR_USERNAME/wake.git wake-src
   cd wake-src
   

```

   Replace `YOUR_USERNAME` with your actual GitHub username. This creates a new folder `wake-src` with the cloned repo.

1. Create the full folder structure:

```bash
   mkdir -p server/tests
   mkdir -p android
   mkdir -p docs/decisions
   mkdir -p .github/ISSUE_TEMPLATE
   mkdir -p .github/workflows
   

```

1. Create placeholder files so Git tracks the empty folders:

```bash
   touch server/tests/.gitkeep
   touch android/.gitkeep
   touch docs/decisions/.gitkeep
   touch .github/workflows/.gitkeep
   

```

1. Create a `.gitignore` — open Cursor, point it at this folder, and use this prompt:
  *"Generate a .gitignore for a monorepo with a Python FastAPI server in /server and an Android Kotlin app in /android."*
   Save the output as `.gitignore` in the root of the project.
2. Commit and push everything:

```bash
   git add .
   git commit -m "chore: initialize monorepo structure"
   git push origin main
   

```

---

### Step 5 — Create the `dev` Branch

All coding work happens on `dev`. `main` is only updated at the end of a completed phase.

```bash
git checkout -b dev
git push origin dev
```

Now protect `main` from accidental direct commits:

1. Go to your repo on GitHub → **Settings** → **Branches**
2. Click **"Add branch protection rule"**
3. In "Branch name pattern" type `main`
4. Check **"Require a pull request before merging"**
5. Click **"Create"**

From now on, nothing can go to `main` without a pull request. This prevents you from accidentally overwriting working code.

---

### Step 6 — Create GitHub Labels

Labels let you filter issues by phase, type, and status on the board.

1. Go to your repo on GitHub → **Issues** tab → **Labels**
2. Delete all the default labels that GitHub pre-creates (click Edit → Delete on each one)
3. Create the following labels using the **"New label"** button. For each one: type the name, type the hex color, click Save.

**Phase labels:**


| Label name | Color hex |
| ---------- | --------- |
| `phase-0`  | `#C5DEF5` |
| `phase-1`  | `#BFD4F2` |
| `phase-2`  | `#D4C5F9` |
| `phase-3`  | `#FEF2C0` |
| `phase-4`  | `#C2E0C6` |
| `phase-5`  | `#F9D0C4` |
| `phase-6`  | `#E4E669` |


**Type labels:**


| Label name | Color hex |
| ---------- | --------- |
| `server`   | `#0075CA` |
| `android`  | `#3DDC84` |
| `infra`    | `#E4E669` |
| `research` | `#D876E3` |


**Status labels:**


| Label name   | Color hex |
| ------------ | --------- |
| `bug`        | `#D73A4A` |
| `blocked`    | `#B60205` |
| `adr-needed` | `#FBCA04` |


---

### Step 7 — Create GitHub Milestones

Milestones group issues by phase and show a percentage completion bar automatically. You will see exactly how far along each phase is at a glance.

1. Go to your repo → **Issues** tab → **Milestones** → **"New milestone"**
2. Create each of the following. Calculate the due date by counting weeks from today (today is March 27, 2026):


| Milestone title              | Due date       |
| ---------------------------- | -------------- |
| `Phase 0 — Setup`            | April 3, 2026  |
| `Phase 1 — Server Daemon`    | April 24, 2026 |
| `Phase 2 — Android + WiFi`   | May 15, 2026   |
| `Phase 3 — Nearby Transport` | June 5, 2026   |
| `Phase 4 — PRoPHET Routing`  | June 19, 2026  |
| `Phase 5 — Security`         | June 26, 2026  |
| `Phase 6 — Pi + Field Test`  | July 10, 2026  |


---

### Step 8 — Create GitHub Projects Board

The Projects board is your daily task view — it shows all issues as cards you drag between columns.

1. Go to your repo → **Projects** tab → **"Link a project"** → **"New project"**
2. Choose the **"Board"** template (not Table or Roadmap)
3. Name it: `WAKE Development Board`
4. Click **"Create project"**
5. You will see 3 default columns (Todo, In Progress, Done). Add two more:
  - Click **"+ Add column"** on the left side and name it `Backlog`
  - Click **"+ Add column"** on the right side of Done and name it `Review`
  - Drag columns so the order is: `Backlog → Todo → In Progress → Review → Done`
6. Click the **Settings** icon (top right of the board) → **Workflows**
  - Enable **"Item added to project"** → set it to move new items to `Backlog` automatically

---

### Step 9 — Create Issue Templates

Issue templates are pre-filled forms that appear whenever you click "New issue." They ensure every issue always has the right information.

1. In your terminal:

```bash
   cd /home/kush/code/wake-src
   git checkout dev
   

```

1. Open Cursor, point it at this project folder, and use this prompt:
  *"Create two GitHub issue template files for a project called WAKE. Save them in .github/ISSUE_TEMPLATE/. The first file is task.md — a template for planned development tasks with fields for: description of the task, what 'done' looks like (acceptance criteria), and checkboxes for: labels assigned, milestone assigned. The second file is bug.md — a template for bugs with fields for: what happened, steps to reproduce it, what you expected to happen, and which phase this was found in."*
2. Commit and push:

```bash
   git add .github/ISSUE_TEMPLATE/
   git commit -m "chore: add GitHub issue templates"
   git push origin dev
   

```

---

### Step 10 — Set Up Notion Workspace

Notion is for notes that do not belong in code — weekly logs and decision records.

1. Go to [notion.so](https://notion.so) and log in
2. Create a new top-level **Page** called `WAKE Project`
3. Inside it, create two sub-pages:
  **Project Log** — a database (table) with these columns:
  - `Week` (number)
  - `Date` (date)
  - `What I did` (text)
  - `What's blocked` (text)
  - `Decisions made` (text)
   Add a first row: Week 1, today's date, "Phase -1 setup", leave the rest blank.
   **ADR Index** — a database (table) with these columns:
  - `#` (number)
  - `Title` (text)
  - `Status` (select: Proposed / Accepted / Superseded)
  - `Date` (date)
  - `File path` (text)
   Leave it empty for now — you will fill it in Step 11.

---

### Step 11 — Write Architecture Decision Records (ADRs)

Before writing any code, document the four major decisions already made for this project. Each decision gets its own markdown file in `docs/decisions/`.

In your terminal:

```bash
cd /home/kush/code/wake-src
git checkout dev
```

Open Cursor and use this prompt for each ADR:
*"Write an ADR markdown file at docs/decisions/NNN-title.md. Use this format: title as H1, then Status (Accepted), Date, then three H2 sections: Context, Decision, Consequences. The decision to document is: [paste below]"*

Create these four files:

**ADR-001** — `001-nearby-over-ble.md`
Decision: Use Google Nearby Connections API instead of raw BLE advertising, BLE scanning, GATT server/client, and WifiP2pManager. Reason: eliminates approximately 8 weeks of device-inconsistent low-level transport code. Tradeoff: requires Google Play Services on all Android devices.

**ADR-002** — `002-json-over-cbor.md`
Decision: Use JSON with base64-encoded payloads for bundle serialization, not CBOR. Reason: JSON is human-readable, debuggable with any tool, and requires no new libraries. Tradeoff: bundles are larger than CBOR. Will revisit if bandwidth becomes a measured problem.

**ADR-003** — `003-laptop-server-first.md`
Decision: Run the WAKE server daemon on a laptop during Phases 0 through 5. Migrate to Raspberry Pi only in Phase 6. Reason: removes Pi hardware as a development bottleneck; 1 phone plus laptop is sufficient to test all protocol logic.

**ADR-004** — `004-epidemic-routing-first.md`
Decision: Implement epidemic routing (forward all bundles to all peers) in Phase 3, then upgrade to PRoPHET probabilistic routing in Phase 4. Reason: epidemic routing is trivial to implement and proves the transport layer works. PRoPHET adds complexity only after the foundation is proven.

After all four files are created:

```bash
git add docs/decisions/
git commit -m "docs: add ADRs 001-004 for initial architecture decisions"
git push origin dev
```

Then open your **ADR Index** in Notion and add all four entries.

---

### Step 12 — Create Phase 0 Issues

The last act of Phase -1 is to pre-populate GitHub with the issues for Phase 0, so your board is ready to go when coding begins.

Go to your repo → **Issues** → **"New issue"** → select the `task` template. Create these 4 issues:


| Title                                                            | Labels               | Milestone       |
| ---------------------------------------------------------------- | -------------------- | --------------- |
| `Install kiwix-serve and verify ZIM search API returns HTML`     | `phase-0`, `server`  | Phase 0 — Setup |
| `Download Simple English Wikipedia ZIM file (~80 MB)`            | `phase-0`, `server`  | Phase 0 — Setup |
| `Create blank Android Studio project (Kotlin + Jetpack Compose)` | `phase-0`, `android` | Phase 0 — Setup |
| `Verify blank app builds and runs on physical device`            | `phase-0`, `android` | Phase 0 — Setup |


After creating them, go to your GitHub Projects board. All 4 issues should appear in **Backlog**. Drag them all to **Todo**. Phase -1 is complete.

---

### Phase -1 Done When

- GitHub Student Developer Pack applied for
- Repo exists at `github.com/YOUR_USERNAME/wake`
- SSH connection to GitHub works (`ssh -T git@github.com` succeeds)
- Folder structure committed and pushed to `main`
- `dev` branch exists and is pushed to GitHub
- `main` branch is protected (requires PRs to merge)
- All 18 labels created in GitHub
- All 7 milestones created with due dates
- GitHub Projects board exists with 5 columns and auto-add enabled
- Issue templates exist at `.github/ISSUE_TEMPLATE/task.md` and `bug.md`
- Notion workspace has Project Log and ADR Index pages with first entries
- 4 ADR files committed to `docs/decisions/` and pushed to `dev`
- 4 Phase 0 issues created in GitHub and visible under "Todo" on the board

---

## Human + AI Collaboration Protocol

This is the loop you run for every issue:

```
1. You open GitHub, pick the next issue from "Todo" on the board
2. You create a feature branch: git checkout -b feature/phase-X-name dev
3. You open Cursor and say:
   "I am working on issue #N: [paste title + description].
    Current relevant files: [list them]. Implement this."
4. AI implements. You review every file it touches.
5. If something looks wrong, ask AI to explain before accepting.
6. When satisfied: git add + git commit (you write the commit message)
7. Push branch, open PR to dev, close the issue in the PR description
8. Merge PR. Repeat.
```

**Rules for AI sessions:**

- Start every session by telling AI the current issue number and phase
- One issue per session where possible — don't mix concerns
- Never let AI commit directly; you always commit
- If AI makes an architecture decision you didn't ask for, stop and write an ADR before continuing
- At the end of a phase, do a session with AI specifically to write tests for that phase

---

## Architecture Decision Records

Every time a significant technical choice is made, create `docs/decisions/NNN-short-title.md`:

```markdown
# ADR-001: Use Google Nearby Connections API instead of raw BLE + WiFi Direct

Status: Accepted | Date: YYYY-MM-DD

## Context
[Why this decision was needed]

## Decision
[What was decided]

## Consequences
[What this enables and what it constrains]
```

Decisions that already need ADRs before Phase 0 starts:

- ADR-001: Nearby Connections API over raw BLE/WiFi Direct
- ADR-002: JSON bundles over CBOR (for now)
- ADR-003: Laptop server during development, Pi deferred to Phase 6
- ADR-004: Epidemic routing first, PRoPHET as Phase 4 upgrade

---

## Weekly Rhythm

**Monday (15 min):**

- Open GitHub Projects board
- Move any stale "In Progress" issues back to "Todo"
- Pick 2–3 issues for the week, move to "In Progress"
- Write one line in Notion Project Log: this week's goal

**Friday (10 min):**

- Close completed issues
- Check milestone % complete (GitHub shows this automatically)
- If phase milestone is 100%: open PR from `dev` → `main`, write phase summary in PR description, merge

---

## Phase 0 — Environment Setup

**Duration:** Week 1 | **Hardware:** Laptop only | **AI sessions:** 0 (manual setup)

**Issues to create (label: `phase-0`, milestone: Phase 0):**

- `#1` Install kiwix-serve and download Simple English Wikipedia ZIM (~80 MB)
- `#2` Verify kiwix-serve search API returns HTML in browser
- `#3` Create Android Studio project (blank Kotlin + Jetpack Compose, verify build)
- `#4` Write ADRs 001–004

**Done when:** Browser shows a kiwix article. Android Studio builds a blank app. Four ADR files exist in `docs/decisions/`.

---

## Phase 1 — Python Server Daemon

**Duration:** Weeks 1–4 | **Hardware:** Laptop only | **AI sessions:** 4–6

**Issues to create (label: `phase-1 server`, milestone: Phase 1):**

- `#5` Set up FastAPI project skeleton with uvicorn, health check endpoint
- `#6` Define bundle JSON schema (request + response structs as Pydantic models)
- `#7` Implement SQLite schema with aiosqlite: `inbound_requests`, `outbound_bundles`, `seen_bundle_ids`
- `#8` Implement chunker: split response payload into ~100 KB numbered chunks
- `#9` Implement PyNaCl Ed25519 keypair generation and bundle signing
- `#10` Implement HTTP endpoints: `POST /request`, `GET /pending`, `GET /bundle/{id}`, `GET /pubkey`
- `#11` Write pytest tests for: bundle create/serialize, chunker, signature verify/fail

**Key technical decisions:**

- Bundle format: JSON + base64 payload (not CBOR — readable and debuggable)
- Request bundle fields: `{node_id, query_id, query_string, timestamp, ttl_seconds, hop_count, signature}`
- Response bundle fields: `{server_id, query_id, chunk_index, total_chunks, content_type, payload_b64, sha256, signature}`

**Done when:** `pytest server/tests/` passes. Sending a `POST /request` with a query string returns chunked response bundles at `GET /bundle/{id}`.

---

## Phase 2 — Android App + Direct Server Link

**Duration:** Weeks 3–7 | **Hardware:** 1 phone + laptop on same WiFi | **AI sessions:** 6–8

**Issues to create (label: `phase-2 android`, milestone: Phase 2):**

- `#12` Create Android Foreground Service skeleton (persistent notification, lifecycle)
- `#13` Define Room DB schema: `bundles` table + `seen_ids` table
- `#14` Implement BundleStoreManager: write/read/evict bundles, LRU policy at 500 MB cap
- `#15` Implement OkHttp client: POST request bundle, poll `GET /pending`, fetch chunks
- `#16` Implement chunk reassembly: detect when all chunks for a `query_id` are present, merge bytes
- `#17` Build UI: Search screen (text field + results), Status screen (storage, last sync)
- `#18` Build Result screen: WebView rendering reassembled HTML from kiwix
- `#19` Integrate Google Tink: verify server Ed25519 signatures before caching any bundle

**Done when:** Phone on same WiFi as laptop — type a query, HTML article renders in WebView. A tampered bundle (manually edited) is rejected.

---

## Phase 3 — Nearby Connections API Transport

**Duration:** Weeks 6–10 | **Hardware:** 2 phones | **AI sessions:** 4–6

**Issues to create (label: `phase-3 android`, milestone: Phase 3):**

- `#20` Add `play-services-nearby:19.1.0` dependency, configure permissions in manifest
- `#21` Implement Nearby advertising + discovery with WAKE service ID `"com.wake.dtn"`
- `#22` Implement ConnectionLifecycleCallback and PayloadCallback
- `#23` Implement manifest exchange: serialize bundle list as `Payload.fromBytes`, parse received manifest
- `#24` Implement bundle transfer: diff manifests, send missing bundles as `Payload.fromFile`
- `#25` Implement epidemic routing: forward all held bundles to every new peer; check `seen_ids` before forwarding

**What this replaces:** All raw BLE and WiFi Direct code from the original roadmap.

**Done when:** Phone A holds a bundle. Phone B comes within range. Phone B receives the bundle via Nearby. Verified in Room DB on phone B.

---

## Phase 4 — PRoPHET Routing Upgrade

**Duration:** Weeks 9–12 | **Hardware:** 2 phones | **AI sessions:** 3–4

**Issues to create (label: `phase-4 android`, milestone: Phase 4):**

- `#26` Add `routing_table` to Room: `(node_id, destination_id, delivery_prob REAL, last_updated INTEGER)`
- `#27` Implement ProphetRouter.kt: direct contact update, transitivity update, aging formulas
- `#28` Add routing table exchange to Nearby manifest exchange step
- `#29` Replace epidemic forwarding decision with PRoPHET decision: only forward if `neighbor.P > self.P`
- `#30` Implement TTL enforcement: drop bundles where `ttl_expires_at < now()`

**Formulas (implement exactly as specified):**

- Direct contact: `P(A→B) = P_old + (1 - P_old) × 0.75`
- Transitivity: `P(A→C) = P_old(A→C) + (1 - P_old(A→C)) × P(A→B) × P(B→C) × 0.25`
- Aging: `P = P_old × 0.98^k`
- Server always advertises `P = 1.0` to itself

**Done when:** Relay phone only forwards a bundle to a peer when logged `P(peer→server) > P(self→server)`. Bundles past TTL are dropped before forwarding.

---

## Phase 5 — Security Hardening

**Duration:** Weeks 11–13 | **Hardware:** 1 phone | **AI sessions:** 2–3

**Issues to create (label: `phase-5 android server`, milestone: Phase 5):**

- `#31` Android Keystore: generate Ed25519 keypair on first launch, derive node_id from public key
- `#32` Sign outbound request bundles with Keystore key; server verifies on receipt
- `#33` Relay verification: verify server signature before caching any response bundle
- `#34` (Optional) Tink HybridEncrypt: encrypt query_string in request with server X25519 public key

**Done when:** Relay rejects a response bundle with a forged/missing server signature. Request bundles are signed with device key that server can verify.

---

## Phase 6 — Pi Deployment + Field Testing

**Duration:** Weeks 12–14 | **Hardware:** 1 phone + Raspberry Pi | **AI sessions:** 2–3

**Issues to create (label: `phase-6 infra`, milestone: Phase 6):**

- `#35` Deploy WAKE daemon + kiwix-serve on Pi as systemd services
- `#36` Configure Pi WiFi hotspot: `hostapd` + `dnsmasq`, SSID `WAKE-Server`, IP `192.168.4.1`
- `#37` Update Android app: configurable server IP (not hardcoded to laptop)
- `#38` Battery optimization: Nearby `STRATEGY_CLUSTER` when idle
- `#39` Field test: direct phone→Pi query
- `#40` Field test: phone out of range, then return, deferred delivery
- `#41` Field test: 2-phone relay path (need to borrow 2nd phone)

**Done when:** Issue #40 passes — a query submitted out of Pi range is delivered when the phone walks back into Pi hotspot range.

---

## Hardware Requirements by Phase


| Phase | Minimum Hardware                                       |
| ----- | ------------------------------------------------------ |
| 0–1   | Laptop only                                            |
| 2     | Laptop + 1 Android phone                               |
| 3–4   | Laptop + 2 Android phones                              |
| 5     | Laptop + 1 phone                                       |
| 6     | Raspberry Pi + 1 phone (2 phones for final relay test) |


