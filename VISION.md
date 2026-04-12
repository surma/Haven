# Haven Product Vision

## Thesis

Haven is a **thin-client operating system for distributed compute, storage, and presence**. The pocket device in your hand is not the computer — it's the point of presence from which you reach the computers, the files, and the agents that do the work. Those computers and files are scattered: a workstation under the desk, a VM in a datacenter, a folder in Google Drive, a camera feed at home, a screen sharing something on the other side of the room. Haven is the single lens through which you see and operate on all of it.

The last decade convinced us that "desktop" and "files" are local nouns. The next one undoes that. Storage lives on whatever is cheapest. Compute runs wherever the data is. Humans, phones, laptops, and AI agents all take turns driving the same shared workflow. Haven treats that world as the default case instead of the exception.

## Identity

The strongest identity Haven has is: **the open-source, privacy-first mobile workspace.** JuiceSSH is dead. Termius went proprietary. ConnectBot is unmaintained. Haven is the only active GPL-licensed terminal app with modern Compose UI, hardware key support, a local Linux environment, a unified cloud file browser, a real media toolchain, and a native GPU-accelerated Wayland desktop — in one APK, no accounts, no telemetry.

The GPL/privacy audience chooses Haven *because* it's open source. Every security choice — encrypted credentials, biometric lock, TOFU host keys, FIDO2 support, local storage only — reinforces this identity. That is the moat and the brand.

## The three primitives

A coherent OS abstraction reduces to three things: a place to keep stuff, a place to run stuff, and a way to move bits between them. Haven is organized around those three primitives.

### 1. Namespace — one filesystem across the universe

The file browser is the **unified namespace**. Local storage, SFTP, SMB, 60+ cloud providers via rclone, and a PRoot-mounted Alpine rootfs are all surfaced as tabs in the same UI, with the same operations: copy, move, rename, delete, open, stream, convert, share. Cross-filesystem copy/move is a first-class operation — drag a file from Google Drive into an SFTP server and it goes through Haven without a local round-trip if the backends support it.

Where files live is an implementation detail. Actions — convert, encrypt, stream, share — apply wherever the file is, not only to local copies. Rclone's HTTP serve + Range requests mean a 5 GB cloud file can be transcoded, previewed, and streamed without ever touching the phone's disk.

### 2. Runtime — any shell, any window, anywhere

The **runtime** is the place you actually run things. Haven exposes four kinds:

- **Local shell** — a full Alpine Linux userland on the phone via PRoot, with a package manager, dev tools, and the option to run agents like Claude Code directly on the device.
- **Remote shell** — SSH, Mosh, Eternal Terminal, and Reticulum transports, with session persistence via tmux/zellij/screen, auto-reconnect, session restore, and a color-coded tabbed terminal that treats all four the same way.
- **Remote desktop** — VNC (with VeNCrypt/TLS), RDP (via IronRDP), tunneled through SSH when you want the wire encrypted.
- **Local desktop** — a native GPU-accelerated Wayland compositor (labwc/wlroots) running in Haven's own process, GLES2-composited via AHardwareBuffer. A real Linux desktop on the phone, distinct from any remote screen.

All four are runtimes in the OS sense: processes with input, output, a filesystem, and a network. Haven's job is to make switching between them feel like switching between terminal tabs on a desktop OS.

### 3. Gateway — the network is the substrate

The **gateway** is how runtimes talk to each other and to the outside world. Haven bundles the primitives of a programmable network:

- **Port forwarding** — Local (`-L`), Remote (`-R`), and Dynamic (`-D`, SOCKS5 proxy server) over any active SSH session, managed live from the UI.
- **ProxyJump** — multi-hop tunnels through bastions and jump hosts, visualised as a tree.
- **Transport proxies** — SOCKS5/SOCKS4/HTTP for reaching `.onion` or corp-restricted endpoints.
- **Network-aware reconnect** — NetworkMonitor triggers an immediate reconnect when WiFi/cellular/VPN flips, instead of waiting for a TCP timeout.
- **Mesh** — Reticulum transport for off-grid connectivity.
- **Service publishing** — the rclone media server, the HLS streaming server, and the Wayland socket (via Shizuku) turn the phone itself into a host that other devices on the LAN can reach.

You should be able to reach any service from any runtime with one configuration step, and the connection should survive network transitions without your workflow fraying.

## The integration thesis — composition is the product

The three primitives are not the product. What you can *do by composing them* is the product. A coherent OS is one where the three compose without friction. Haven's design target: every workflow below should be one flow inside the app.

- Tap a 4K MKV sitting in Google Drive → ffmpeg reads it over HTTP from rclone VFS → the frame preview appears in 3 seconds → tweak brightness → pick "H.264, back to cloud" → the converted file appears in the same Drive folder without ever touching local disk.
- SSH to the workstation → forward port 5901 → tap the VNC profile that targets `localhost:5901` → desktop opens in the same app, keyboard and clipboard shared.
- In the local PRoot shell, run `claude --cwd /sdcard/projects/foo`. Claude reads files, runs `git push` over the SSH agent you forwarded from your laptop, and you watch it work on the same screen where the SSH tab lives.
- Copy a log directory from an S3 bucket to an SFTP server: long-press → cut → switch tab → paste. Rclone does the server-side copy when possible; otherwise Haven streams it through.
- Stream a video from a cloud folder to the Chromecast across the room via HLS, copy the LAN URL from the snackbar, paste it into a message to your partner's phone so they can watch too.

None of these require leaving Haven, installing a second app, or running a `curl | ssh` incantation. Each of them uses two or more of the three primitives. This is the thesis.

## The Claude era — agents are a first-class user

LLM agents are a new class of actor, and they need the same primitives a human does: credentials, shell access, a filesystem, a network. An agent is just a very persistent process that wants to SSH, read files, run commands, and transform media — exactly what Haven already mediates for a human.

What this means in practice:

- **Agents in the local runtime**: PRoot is a first-class place to run `claude-code`, local dev tools, language runtimes, and AI CLIs. No root, no Termux, no rebuild needed.
- **Agents in a remote runtime**: SSH to the workstation where your real agent lives, keep the session alive across network drops, and come back to a session that remembers what the agent was doing.
- **Credentials that humans and agents share**: one encrypted keystore, SSH agent forwarding so the remote agent can use keys that never leave the phone, host key TOFU so a compromised gateway can't silently inject itself.
- **Files that humans and agents both operate on**: the unified namespace means "the project folder" is one path whether the agent is running in PRoot, on the workstation, or pulling from cloud storage.
- **Observation**: the terminal tab, the file browser, and the persistent notification give a human operator a clear view of what their agent is doing and where. Haven is the dashboard, not a black box.

Haven is *not* building an AI assistant. It's building the layer that makes AI assistants useful wherever a human wants to point them — mobile, distributed, multi-backend. The agent sees the same OS abstraction the human does.

## Development priorities

The thesis is clear; the work is making it feel seamless. Priorities are ordered by leverage, not by effort.

### 1. Composition polish — make the three primitives snap together

Whenever two primitives meet, there should be zero friction. Current gaps:

- **SFTP/SMB media** should work through the same HTTP-streaming trick as rclone so convert/preview/stream/tap-to-play work for every backend, not just rclone + local. Building an ffmpeg-with-libssh would unlock this in one move.
- **Agent forwarding UX** — the plumbing exists; the story of "forward my phone's keys to the remote agent and be able to trust it" needs to be a dialog, not a config file.
- **Workspace profiles** — "Work" opens SSH tab + port forwards + SFTP sidebar + Wayland tab + Claude Code pane in one tap, resumes to the same composition next launch.
- **Desktop ↔ file browser ↔ terminal** — cross-tab actions (drag a file from the SFTP tab into the native Wayland compositor, copy output from a terminal into the convert dialog).

### 2. The namespace as the action surface

The file browser is Haven's highest-leverage surface because it's where every backend converges. Every action that applies to a file should be available on every file, regardless of where it lives.

- **Universal action set** — convert, preview, stream, play, encrypt, share link, copy path, inspect metadata — consistent across local, rclone, SFTP, SMB, PRoot. Gaps (e.g. encrypt isn't implemented yet, stream doesn't work on SFTP/SMB, metadata inspection is shallow) are bugs against the thesis.
- **age file encryption** — end-to-end encryption for files in any backend; keys live in Haven's keystore, operate wherever the ciphertext lives.
- **Trim / cut / resolution picker** — finish the media toolchain so the convert dialog is a real mobile NLE, not just a transcoder.

### 3. The runtime story — PRoot is the agent host

The local PRoot rootfs is the differentiator nobody else ships. It's where an agent can run persistently on the phone without leaving Haven.

- **Curated dev stacks** — one-tap Python/Node.js/Rust/Go, pre-tested.
- **Claude-ready environment** — PRoot preset that installs node + claude-code + useful tooling, with a tab that opens directly into an `claude` prompt.
- **sshfs inside PRoot** — mount remote filesystems so local tools (and local agents) operate transparently on remote files.
- **Storage management** — rootfs images grow; show disk usage, offer cleanup, support external storage.

### 4. The Wayland desktop — a second runtime

Haven's native Wayland compositor is the most technically differentiated piece of work. Now that keyboard, GPU, and window management are shipped, the next step is populating it:

- **Multiple windows** — launch additional Wayland clients concurrently.
- **GL client passthrough** — virgl/venus so GPU-accelerated apps in PRoot render natively.
- **Standalone socket** — let external clients (Termux, chroot, foreign runtimes) connect.

### 5. Network resilience and security as brand

- **Background keepalive resilience** — Doze mode / app standby / battery optimisation is the biggest source of "session died" complaints. Document, automate, and expose clear recovery actions.
- **Per-profile authentication** — high-security connections require auth each time.
- **Audit log UI** — surface ConnectionLog so privacy-conscious users can verify the app's behaviour.
- **Secrets-clean logs** — automate the scrub of credentials from verbose logs and crash reports.

## Scope boundaries — what Haven is not

A tight scope is how a small project stays coherent. Haven deliberately does not:

- **Build an editor** — vim/nano/micro in PRoot or on the remote shell is the editor. Building an in-app text editor is a tar pit.
- **Build a media player** — the OS already has VLC, MX Player, etc. Haven hands off via intents. We are the file transport and transformation layer, not the playback layer.
- **Reimplement tmux/zellij** — split panes, scrollback search, session persistence are session-manager features. Haven integrates with them via SessionManagerRegistry instead of competing.
- **Build provider-specific features** — rclone is the abstraction. No Google Drive-specific sharing UI, no Dropbox versioning, no S3 object lifecycle panel. The provider list stays uniform.
- **Build collaboration** — shared sessions, voice, screen sharing. Out of scope for a single-developer project and orthogonal to the identity.
- **Optimise for tablets/desktops first** — get the phone-in-one-hand experience right before chasing form factors.
- **Ship an AI assistant** — Haven provides the substrate agents run on; it does not ship its own model, API, or chat UI. The user brings the agent.

## Architectural direction

Think of Haven as three layers, each of which must remain small and sharp:

1. **Primitives** (namespace, runtime, gateway). Each has a clean Kotlin API and wraps exactly one underlying technology per function. No cross-layer leakage: the file browser doesn't know ffmpeg exists; ffmpeg doesn't know rclone exists; they meet through HTTP URLs and process stdin/stdout.

2. **Composition surfaces** (terminal tab, file browser tab, desktop tab, convert dialog, port forward dialog). These are where primitives combine into workflows. The goal is that adding a new primitive — say, a new backend — lights up every composition surface for free.

3. **Identity and trust** (keystore, screen lock, TOFU, secrets hygiene). This is the cross-cutting layer that earns users' confidence in putting their credentials on the phone in the first place. Every feature must pay its security rent.

A public library succeeds not by having every book, but by having the right books, organized well, in a building that's pleasant to be in. Haven's books — protocols, backends, codecs — are sufficient. The work now is in the organisation (composition surfaces, workspace profiles, cross-tab actions) and the building (touch interface polish, gesture reliability, battery-friendliness).

**Width is sufficient. Composition is the opportunity.** The phone is the thin client; Haven is the thin-client OS; the cloud and your servers and your agents are the computer.
