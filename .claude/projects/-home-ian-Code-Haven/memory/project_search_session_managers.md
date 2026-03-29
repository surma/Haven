---
name: Search with session managers
description: Terminal search limitation — tmux/zellij/screen own scrollback, Haven search only sees viewport. Should forward search keys to session manager instead.
type: project
---

Haven's scrollback search (v3.4.0) only searches termlib's local buffer. When a session manager (tmux, zellij, screen) is active, it manages the scrollback and Haven only has the current viewport content.

**Why:** Session managers intercept and manage the scrollback buffer. Haven's terminal emulator only receives the rendered viewport, so local search finds nothing useful in these sessions.

**How to apply:** When implementing session-manager-aware search, check the connection profile's `sessionManager` field (same one used by `sendRedrawIfZellij()`). If a session manager is active, the search button should send the native search key sequence:
- tmux: `\x02[` then `?` (backward) or `/` (forward)
- zellij: `\x13` (Ctrl+S) for search mode
- screen: `\x01[` then `/`

The current local search still works for plain SSH sessions and local shells without a multiplexer.
