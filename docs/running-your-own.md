# Running your own relay

Start to finish: from nothing, to two handsets talking to each other.

There is no PTTdroid service to sign up for. The relay is the whole back end — one process, no
database, nothing to back up — and you run it. This page is the walkthrough; once it works,
[configuration.md](configuration.md) is the reference for every setting and
[deployment.md](deployment.md) is the reference for every way to deploy.

## What you are setting up

The relay does two things: it keeps each channel's members apart, and it enforces one talker per
channel. Audio does not touch disk and nothing is stored between restarts.

It needs a machine that stays awake and that the handsets can reach. That is the only real
requirement — a laptop on the same Wi-Fi is enough to start with, and a Raspberry Pi or the
smallest VPS you can rent is enough forever.

Audio is uncompressed 16 kHz mono, so a talker sends about **32 kB/s** and the relay forwards that
to everyone else on the channel: ten people talking one at a time is ~32 kB/s in and ~290 kB/s out,
and nothing at all while nobody is holding the button. The work is copying bytes, not encoding
them, so CPU is not what runs out first — upstream bandwidth is.

## Pick your situation

| Everyone is… | Do this | Section |
|---|---|---|
| On one Wi-Fi, and you have a spare machine on it | Run it on that machine, plaintext | [1](#1-start-it) → [5](#5-talk) |
| On one Wi-Fi, but you'd rather not trust the network | Same, then turn on encryption | + [Encrypt it](#encrypt-it) |
| Spread across different networks | Put it on a VPS, or open a tunnel | [Reaching it from anywhere](#reaching-it-from-anywhere) |
| Only ever two people, occasionally | Skip all of this — one phone can host the relay itself | [No machine at all](#no-machine-at-all) |

Whatever you pick, **[2](#2-find-the-address)–[5](#5-talk) are the same**: find the address, open
the firewall, point the app at it, talk.

---

## Before you start

You need **one** of:

- **Docker** with the Compose plugin — `docker --version && docker compose version`
- **JDK 21** — `java -version`. Gradle will fetch a matching JDK if you have none

And the code:

```bash
git clone https://github.com/devapro/ptt-server.git
cd ptt-server
```

## 1. Start it

```bash
cp .env.example .env
```

Open `.env` and set one value:

```properties
PTT_AUTH_TOKEN=<paste the output of: openssl rand -base64 24>
```

Everyone who connects sends this. **Skip it only if the network is genuinely yours alone** — the
relay carries live microphone audio, and without a token, being able to reach the port *is* the
whole of access control. The server will warn you at startup if you leave it blank.

```bash
docker compose up -d --build      # about a minute the first time
```

Confirm it is up:

```bash
$ docker compose ps
NAME         IMAGE              SERVICE      STATUS                   PORTS
ptt-server   ptt-server:local   ptt-server   Up 3 seconds (healthy)   0.0.0.0:8000->8000/tcp

$ curl -s localhost:8000/health
{"status":"ok","channels":0,"sessions":0,"protocolVersion":1}
```

(`docker compose ps` also prints COMMAND and CREATED columns; they are cut here for width.)

`(healthy)` and that JSON mean the relay is listening. If the status says `starting`, wait a few
seconds; if it says `unhealthy`, go to [Troubleshooting](#troubleshooting).

The log tells you what it decided:

```
$ docker compose logs
ptt-server  | 2026-08-30 10:46:03.302 INFO  Main - Starting PTT relay — channels 1..99, max audio frame 8192 bytes, auth required
ptt-server  | 2026-08-30 10:46:03.305 INFO  Main -   ws://0.0.0.0:8000/channel/<n>
ptt-server  | 2026-08-30 10:46:03.893 INFO  i.k.server.Application - Responding at http://0.0.0.0:8000
```

`auth required` means the token took. `auth OFF` means it did not, and the next line spells out
what that costs:

```
ptt-server  | … WARN  Main - PTT_AUTH_TOKEN is not set: anyone who can reach this port can join a
channel and listen. Set it before exposing the relay beyond a trusted LAN.
```

The usual cause is editing `.env.example` instead of `.env`.

<details>
<summary>Without Docker</summary>

```bash
PTT_AUTH_TOKEN='…' ./gradlew run          # foreground, Ctrl-C to stop
```

Or build a distribution you can run anywhere with a JRE:

```bash
./gradlew installDist
PTT_AUTH_TOKEN='…' build/install/PTTdroidServer/bin/PTTdroidServer
```

Every setting is an environment variable — there is no config file and no command line. The full
list is in [configuration.md](configuration.md). To keep it running across reboots, use the
systemd unit in [deployment.md](deployment.md#without-docker-systemd).
</details>

## 2. Find the address

The handsets need the address of **this machine on the network they share with it**. Not
`localhost`, not `127.0.0.1` — those mean "the phone itself" once you type them into a phone.

```bash
hostname -I | awk '{print $1}'        # Linux
ipconfig getifaddr en0                # macOS, Wi-Fi
ipconfig                              # Windows — "IPv4 Address" under your adapter
```

You want something like `192.168.1.20`, `10.0.0.42` or `172.16.x.x`. Write it down with the port:

```
192.168.1.20:8000
```

> **Running a client in an Android emulator?** The emulator's address for the machine hosting it
> is **`10.0.2.2`**, always — its own `localhost` is the emulator, and the LAN IP above is not
> reachable from its virtual network either. `10.0.2.2:8000` is also what the app ships as
> **Relay → Default**, so an emulator needs no configuring at all against a default relay.

## 3. Open the firewall

The relay binds every interface, but the operating system in front of it may not let anything
through.

```bash
sudo ufw allow 8000/tcp                                    # Ubuntu / Debian
sudo firewall-cmd --add-port=8000/tcp --permanent && sudo firewall-cmd --reload   # Fedora / RHEL
```

macOS prompts the first time something listens — allow it. Windows Defender does the same; if you
dismissed the prompt, add an inbound rule for TCP 8000.

Check from *another* machine on the same network, not from the one running it:

```bash
curl -s 192.168.1.20:8000/health
```

If that answers, the phones can reach it too. If it hangs, it is the firewall or the network —
see [Troubleshooting](#troubleshooting).

## 4. Point the app at it

On each handset:

1. **Settings → Relay → Custom**
2. In **Server address**, type what you wrote down: `192.168.1.20:8000`
3. **Settings → Security → Access token** — the `PTT_AUTH_TOKEN` from `.env`
4. Check the line under the address reads **`Will connect to ws://192.168.1.20:8000/channel/1`**
5. **Save and reconnect**

That preview line is worth a second look before you save: it is the exact URL the app will dial,
and a typo is visible there long before it turns into a connection that quietly does not work.

The address box takes more than a bare address, which matters later — you can paste a whole URL
from a server log or a tunnel and it will take the scheme and port out of it. For now `host:port`
is all you need.

## 5. Talk

Put two handsets on the **same channel** — the number under Identity, 1 to 99. Channels are
completely separate; the relay creates one the moment somebody joins it and forgets it when the
last person leaves.

The word on the button tells you where you are:

| The button says | What that means |
|---|---|
| `OFFLINE` | Not connected. The banner above says why |
| `LINKING` | Connecting or reconnecting |
| `HOLD` | Connected, channel free — press and hold to talk |
| `WAIT` | You asked for the floor and the server has not answered yet |
| `ON AIR` | You have the floor. You are being heard |
| `BUSY` | Someone else has the floor. Your button is disabled on purpose |

Hold the button on one handset. The other should switch to `BUSY` and name the talker within a
fraction of a second, even if the audio is silent. That is the floor working, which is the part
that matters — audio is the easy half.

Watch it happen server-side:

```bash
$ docker compose logs -f
ptt-server  | … INFO  ChannelRoutes - Session 05d78ccd… (Ann) joined channel 1 — 1 peer(s)
ptt-server  | … INFO  ChannelRoutes - Session 9f2b41e7… (Bob) joined channel 1 — 2 peer(s)
```

and count who is connected at any time:

```bash
$ curl -s localhost:8000/health
{"status":"ok","channels":1,"sessions":2,"protocolVersion":1}
```

> **Emulator microphones capture silence.** Two emulators will show the floor changing hands
> perfectly and you will hear nothing. That is the emulator, not the relay. Verify audio on real
> handsets.

---

## Keeping it running

`docker-compose.yml` sets `restart: unless-stopped`, so the relay comes back after a reboot or a
crash on its own. The rest of day-to-day:

```bash
docker compose logs -f          # follow
docker compose restart          # restart, keeping the certificate volume
docker compose down             # stop
git pull && docker compose up -d --build    # update
```

Updating is safe to do while people are connected — they reconnect on their own, with backoff.
The one thing an update does *not* touch is the TLS keystore, which lives in a named volume for
exactly that reason.

## Locking it down

`PTT_AUTH_TOKEN` is one shared secret for everybody. There are no accounts, no per-handset
credentials and no way to revoke one person: changing the token means telling everyone the new
one. That is a deliberate limit, not an oversight — this is a walkie-talkie for a group that
already trusts each other.

To change it:

```bash
$EDITOR .env                    # new PTT_AUTH_TOKEN
docker compose up -d            # picks up .env, no rebuild needed
```

Every connected handset drops and cannot rejoin until its **Settings → Security → Access token**
matches.

Two more knobs worth setting before the relay is reachable from anywhere you do not control:

| Variable | Why |
|---|---|
| `PTT_MAX_SESSIONS_PER_CHANNEL` | Caps a channel (default 32) so one client cannot open sockets until the process falls over |
| `PTT_MAX_CHANNEL` | Fewer channels is fewer places for someone who has the token to sit unnoticed |

There is no rate limiting on the handshake. If the relay is permanently public, put something in
front of it that has some.

## Encrypt it

Plaintext `ws://` is fine on a network you control and wrong anywhere else. There is no domain to
prove ownership of on a LAN, so the relay generates its **own** certificate and the app trusts
that exact certificate rather than a chain of authorities:

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
./deploy/fingerprint.sh
```

```
Certificate fingerprint (SHA-256):

  7E:CF:C4:0A:AD:8D:2C:DD:76:96:CD:8B:1E:E3:7A:0F:B4:E8:B9:59:24:62:00:5B:C9:EB:6C:EB:F8:EE:8E:07
```

On each handset:

1. **Settings → Relay → Custom** → `192.168.1.20:8443` — note the port changed
2. **Settings → Security → Encrypted connection** → on
3. **Certificate fingerprint** → paste the value above. Colons and capitalisation do not matter
4. Check the preview now reads `wss://…`, and save

Two things to know about this:

- **It is stricter than a certificate authority**, not weaker. A pinned fingerprint admits one key
  and nothing else, where a CA admits anything it has ever signed.
- **It does not rotate.** Replacing the relay's keypair locks out every handset already paired
  until each one is given the new fingerprint. That is why the keystore lives in a named volume
  and is never regenerated on a restart. Add your LAN address to `PTT_TLS_SAN` in `.env` *before*
  first boot if you also want `curl` and browsers to be happy with it.

The TLS overlay turns the plaintext port **off** rather than leaving it unpublished — a relay
speaking both is one firewall mistake away from carrying audio in the clear.

Check it from outside, with tools you already have:

```bash
echo | openssl s_client -connect 192.168.1.20:8443 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256
```

That should print the same value `fingerprint.sh` did. If it does not, something is between you
and the relay.

## Reaching it from anywhere

Three ways, in the order most people should try them:

### A tunnel (no server, no port forwarding, ~2 minutes)

```bash
$EDITOR .env                    # NGROK_AUTHTOKEN from dashboard.ngrok.com
docker compose -f docker-compose.yml -f docker-compose.ngrok.yml up -d --build
./deploy/ngrok-url.sh
```

The script prints the public URL and what to do with it: **Relay → Custom**, paste the whole
`https://…` address, and the app takes port 443 and encryption out of it by itself. Leave the
fingerprint empty — ngrok presents a publicly trusted certificate, so there is nothing to pin.

This overlay **requires** `PTT_AUTH_TOKEN` and refuses to start without it. A tunnel is a public
address; an unguessable URL is not access control. It also republishes the relay's own port on
loopback only, so the tunnel is the only way in.

Set `NGROK_DOMAIN` to a reserved domain — free accounts get one — or the address changes on every
restart and everyone re-types it.

### A VPS

Any host with SSH and Docker. There is no address in the script; it reads the environment:

```bash
PTT_DEPLOY_HOST=relay.example.com \
PTT_DEPLOY_COMPOSE_FILES="docker-compose.yml docker-compose.tls.yml" \
./deploy/deploy.sh
```

It checks the host is reachable and has Docker before touching anything, copies the repository
across, brings the project up, and waits for the container to report healthy — dumping the logs
and failing if it does not. Re-run it to update: the transfer is incremental and the certificate
volume survives, so nobody has to re-pair.

`PTT_DEPLOY_DRY_RUN=1` prints what it would do and stops. Full variable list in
[deployment.md](deployment.md#deploying-to-a-remote-host).

If the VPS has a real domain, a reverse proxy with a real certificate is the nicest option of all:
the handsets then need **Encrypted connection on and no fingerprint**, and nothing to re-pair,
ever.

### Forwarding a port on your router

It works, and it is the option to be careful with. If you do it: forward to the **TLS** port only,
set a token, and accept that your home IP is now a listening device on the public internet. A
tunnel gets you the same reach without that.

## No machine at all

For two or three people on one Wi-Fi, one handset can be the relay. In the app:

- On the phone that will host: **Settings → Hands-free → Host a relay on this device**, then
  **Relay → Custom → `127.0.0.1`**
- On every other phone: **Relay → Custom →** that phone's Wi-Fi address, e.g. `192.168.1.31:8000`

It speaks the same protocol as this repository and enforces the same one-talker rule. What it
cannot do is encryption — it serves plaintext only — and it stops when that phone does.

---

## Troubleshooting

Work down from the top; each check rules out everything above it.

| What you see | What it usually is |
|---|---|
| `curl localhost:8000/health` fails on the relay machine | The relay is not running. `docker compose ps`, then `docker compose logs` |
| Startup log says **"PTT_HTTP_ENABLED and PTT_TLS_ENABLED are both off"** | Both connectors disabled. The TLS overlay turns plaintext off, so enable one |
| Health works locally, `curl <lan-ip>:8000/health` from another machine hangs | Firewall on the relay machine ([step 3](#3-open-the-firewall)), or the two machines are not on the same network |
| Health works from a laptop, phone shows `OFFLINE` | The phone is on mobile data or a guest network. Guest Wi-Fi commonly blocks device-to-device traffic entirely |
| Phone shows `OFFLINE` **immediately**, banner mentions `CLEARTEXT` | Encryption is on in the app but the relay is serving `ws://`. Turn **Encrypted connection** off, or start the TLS overlay |
| Banner says the certificate fingerprint does not match | The keystore was regenerated — deleting the `ptt-certs` volume does this. Re-run `./deploy/fingerprint.sh` and paste the new value into every handset |
| Banner mentions the certificate has expired, or is not yet valid | The clock on the relay machine or the handset is wrong. Check both before touching the certificate |
| Connects, then drops straight away | Token mismatch. The relay logs the rejection; check for a typo, and note the app trims spaces so a stray one is not the cause |
| `unsupported_version` | Client and relay are from different releases. Update both |
| `channel_full` | `PTT_MAX_SESSIONS_PER_CHANNEL` reached (default 32) |
| Everything connects, the button says `HOLD`, nobody hears anything | Almost always the microphone: check the app has the permission, and remember emulators capture silence |
| Two handsets both say `HOLD` but never see each other | Different channels, or different relays. The **Will connect to** line in Settings shows both at once |
| It worked yesterday, the tunnel address changed | ngrok hands out a new address per restart unless `NGROK_DOMAIN` is set |
| `docker compose up` fails on the certificate volume | Something else is using it, or it was created by a different compose project. `docker compose down -v` deletes it — which also invalidates every handset's pin |

Two commands answer most of the rest:

```bash
docker compose logs -f            # every join, leave and rejection, as it happens
curl -s localhost:8000/health     # channels and sessions right now
```

## Where to go from here

| | |
|---|---|
| [configuration.md](configuration.md) | Every environment variable, with ranges and defaults |
| [deployment.md](deployment.md) | Docker internals, systemd, remote deploy, security posture |
| [protocol.md](protocol.md) | The wire protocol, if you want to write another client |
| [architecture.md](architecture.md) | How the relay is put together inside |
