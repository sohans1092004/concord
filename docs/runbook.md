# Concord runbook: from a fresh machine to the trial, and running it

The trial host is one **AWS EC2 t4g.small** in **ap-southeast-2 (Sydney)**, paid from the AWS
free plan's credits:
- 2 vCPU, 2 GB RAM, arm64, 30 GB gp3;
- **Ubuntu 26.04 LTS**; the containers themselves stay on Ubuntu 24.04 (noble) and Alpine images;
- Elastic IP **52.62.82.248**, hostname **concord-serve.duckdns.org** (DuckDNS, free);
- certificates from Let's Encrypt via Caddy; backups to **Backblaze B2** (free 10 GB);
- **the VM never builds anything.** CI builds the images for every commit on `main`, after all
  tests pass, and publishes them as `ghcr.io/sohans1092004/concord-server` and `concord-backup`,
  tagged with the commit SHA. The VM pulls the SHA that `.env` pins (`CONCORD_TAG`).

Commands in `code` run on the VM unless marked **(laptop)**.

Contents:
1. [Money: stay at zero spend](#1-money-stay-at-zero-spend)
2. [The VM, its firewall and its address](#2-the-vm-its-firewall-and-its-address)
3. [SSH from Windows](#3-ssh-from-windows)
4. [Swap, Docker and the code](#4-swap-docker-and-the-code)
5. [The backup key and the B2 bucket](#5-the-backup-key-and-the-b2-bucket)
6. [Settings](#6-settings)
7. [Start](#7-start)
8. [Smoke test](#8-smoke-test)
9. [Monitoring](#9-monitoring)
10. [Day to day](#10-day-to-day)
11. [Memory on a 2 GB machine](#11-memory-on-a-2-gb-machine)
12. [If the machine dies](#12-if-the-machine-dies)
13. [Alternatives: Oracle, GCP](#13-alternatives-oracle-gcp)
14. [Reliant](#14-reliant)

## 1. Money: stay at zero spend

The account is on AWS's **free plan**: it spends the sign-up credits ($100 here), and nothing
can be charged to the card while it stays on that plan.

- **Never upgrade to a paid plan.** The console offers it from time to time (Billing, the
  account menu, and when credits run low). On the free plan, AWS stops or closes things
  instead of charging. A paid plan would bill the card.
- **The credits are finite: about $20 a month at this size.** That's your estimate covering the
  instance, the 30 GB gp3 volume and the public IPv4 address (AWS bills every public IPv4,
  including an attached Elastic IP). So $100 lasts about 5 months, well past the two-week trial.
- **Check the balance.** In the console, open **Billing and Cost Management → Credits**: the
  remaining amount and the expiry date. The console home's free plan widget shows the same, and
  how many days the plan has left. Look once a week.
- **Keep a zero-spend budget**, so an email arrives if anything ever costs real money:
  1. Go to **Billing and Cost Management → Budgets → Create budget**.
  2. Choose **Use a template → Zero spend budget**.
  3. Enter your email and create it.
- **Release Elastic IPs you are not using.** An Elastic IP costs credits whether or not it is
  attached. Keep exactly one (52.62.82.248). If you ever replace the instance, move that one
  (section 12) rather than allocating another. Release extras under **EC2 → Elastic IPs →
  select → Actions → Release**.
- **Delete what you stop using:** stopped instances still pay for their volume, and snapshots
  cost per GB. At the end of the trial, see section 10's last row.

## 2. The VM, its firewall and its address

Already done. Record it here so a rebuild matches:

1. **Instance:** EC2 → Launch instance.
   - Name `concord`; image **Ubuntu Server 26.04 LTS (arm64)**; type **t4g.small**.
   - Key pair `concord` (`.pem`, downloaded once).
   - Storage **30 GB gp3**.
2. **Security group** (EC2 → Security Groups → the instance's group → Inbound rules), exactly:

   | Type | Port | Source | Why |
   |---|---|---|---|
   | SSH | 22 | **My IP** (your current address, `/32`) | only you |
   | HTTP | 80 | `0.0.0.0/0` | Let's Encrypt's check and the redirect to HTTPS |
   | HTTPS | 443 | `0.0.0.0/0` | the app |

   - Outbound: the default (all), for Let's Encrypt, B2, image pulls and apt.
   - If your home IP changes, SSH stops working: edit the SSH rule and choose **My IP** again.
   - The VM itself has no firewall rules (Ubuntu's `ufw` is off on AWS images); leave it that way.
3. **Elastic IP:**
   - Go to **EC2 → Elastic IPs → Allocate**, then **Associate** it with the instance.
   - It's 52.62.82.248: the address stays the same across stops, starts and reboots.
4. **DuckDNS:**
   - At https://www.duckdns.org, `concord-serve` → **52.62.82.248**.
   - The IP is fixed, so no updater is needed.
   - **(laptop)** Check it with `nslookup concord-serve.duckdns.org`, which must answer 52.62.82.248.

**Where the testers are:** India. Sydney adds roughly 100–150 ms per round trip compared with a
nearby region, so a tester's edit takes that much longer to reach the other screens. Measure it
(section 8) and report it against the 200 ms target as it is. A miss caused by distance is a
finding about the region, not a bug.

## 3. SSH from Windows

**(laptop)**, in PowerShell, once:

```powershell
icacls concord.pem /inheritance:r
icacls concord.pem /grant:r "$($env:USERNAME):(R)"
```

These give only you read access. OpenSSH refuses a key that others can read.

Then, every time:
```powershell
ssh -i concord.pem ubuntu@52.62.82.248
```

## 4. Swap, Docker and the code

**Swap (2 GB, a safety net only).** The stack is sized to fit in RAM (section 11). Swap only
catches a short spike instead of letting the kernel kill a process:
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-concord.conf && sudo sysctl --system | grep swappiness
free -m          # Swap: 2047 total
```
`vm.swappiness=10` keeps the kernel from swapping out memory that is in use; it swaps only under
real pressure.

**Switch off what an EC2 server doesn't use**, to make room. Measured on this VM, with only the OS
running: `used` was 503 MiB after a reboot, and 458 MiB after this.
```bash
sudo systemctl disable --now ModemManager udisks2 multipathd.service multipathd.socket
free -m          # Mem: total 1834, used about 460
```
- **ModemManager** manages cellular modems; **udisks2** auto-mounts desktop drives;
  **multipathd** handles multipath SAN storage, while the root disk is a single EBS volume.
- Leave **snapd** (AWS's SSM agent is a snap) and **unattended-upgrades** (security updates).
- With these off: the OS's ~460 MiB plus Concord's limits of 1,336 MiB = 1,794 MiB, which fits
  in the 1,834 MiB of RAM even if every container sat at its limit.

**Docker, from Docker's own repository.** It supports Ubuntu 26.04 (`resolute`) on arm64: on
2026-10-17 it listed `docker-ce` 29.9.1 and `docker-compose-plugin` 5.6.0 for
`resolute/stable/binary-arm64`.
```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker ubuntu && exit        # log out and in again, so the group applies
```
After logging back in:
```bash
docker run --rm hello-world && docker compose version     # "Hello from Docker!", then v5.x
```
**If** `apt-get update` says the Docker repository has no Release file for this release (it
does today), use Ubuntu's own packages instead:
`sudo apt-get install -y docker.io docker-compose-v2 docker-buildx`.

**The code:** the VM needs only the compose files and scripts from it; the programs come as
images.
```bash
git clone https://github.com/sohans1092004/concord.git ~/concord
```

## 5. The backup key and the B2 bucket

**The age key** encrypts the backups. Only its *public* half goes on the VM.
1. **(laptop)** With `age` installed (`winget install FiloSottile.age`):
   ```powershell
   age-keygen -o concord-backup.key
   ```
   It writes a private key (`AGE-SECRET-KEY-1…`) to the file and prints the public key (`age1…`).
2. **Save the private key** in your password manager, plus one offline copy. Without it no
   backup can be restored. It never goes on the VM.
3. The public key is `AGE_RECIPIENT` in step 6.

**Backblaze B2:**
1. Sign up at https://www.backblaze.com/sign-up/cloud-storage. The free 10 GB says it needs no
   card; if it asks for one, stop and tell me.
2. Go to **B2 Cloud Storage → Buckets → Create a Bucket**:
   - name `concord-backups-serve` (bucket names are global; add a suffix if it's taken);
   - Files: **Private**;
   - encryption: off (the backups are already encrypted);
   - object lock: off.
3. Set the bucket's **Lifecycle Settings** to **Keep only the last version of the file**.
4. Go to **Application Keys → Add a New Application Key**:
   - name `concord-vm`;
   - access to that bucket only;
   - **Read and Write**.
5. Copy the **keyID** and the **applicationKey**. The key is shown only once.

## 6. Settings

```bash
cd ~/concord/deploy/prod
./gen-env.sh concord-serve.duckdns.org you@example.com    # writes .env with fresh random secrets
nano .env
```

In `.env`:
1. Set `AGE_RECIPIENT=age1…`, the public key from step 5.
2. Under "(a) Backblaze B2", remove the `#   ` from its four lines and fill them in:
   ```
   RCLONE_CONFIG_REMOTE_TYPE=b2
   RCLONE_CONFIG_REMOTE_ACCOUNT=<keyID>
   RCLONE_CONFIG_REMOTE_KEY=<applicationKey>
   BACKUP_REMOTE=REMOTE:concord-backups-serve/concord
   ```
   Then delete the two lines that still say `CHANGE_ME` (`RCLONE_CONFIG_REMOTE_TYPE=CHANGE_ME`,
   `BACKUP_REMOTE=CHANGE_ME`). Leave the (b) Oracle lines commented.
3. Optional: set `BACKUP_PING_URL`.
   - Create a free check at https://healthchecks.io, period 1 day, grace 2 hours.
   - Paste its ping URL. It emails you if a night's backup never arrives.

**The version** is `CONCORD_TAG` in `.env`: `gen-env.sh` sets it to the commit you cloned.
- That commit's images exist once its CI run on GitHub (**Actions → ci**) is green, including
  the two "publish images" jobs. That takes about 15 minutes after a push.
- **To use another version,** put that commit's full 40-character SHA there. Never `latest`.

**Keep a copy of `.env` in your password manager.** It holds the database password and the
invite code. `.env` is never committed (`.gitignore`), and production refuses to start with any
development value (`Config.checkPublic`).

## 7. Start

```bash
cd ~/concord/deploy/prod
docker compose pull                        # the arm64 images for CONCORD_TAG; a minute or two
docker compose up -d --wait
docker compose ps                          # every service "healthy" or "running"; migrate "exited (0)"
docker compose logs caddy | grep -i "certificate obtained"
curl -fsS https://concord-serve.duckdns.org/healthz && echo    # ok
docker compose exec backup /app/backup/backup.sh               # a first backup now; prints backup_done
free -m && docker stats --no-stream        # compare with section 11
```

Then prove the backup restores. **(laptop)**, from a clone with the same `.env` copied over (the
images exist for x86 too):
```bash
cd concord/deploy/prod && docker compose pull backup gateway
./restore.sh ~/concord-backup.key latest     # ends "RESTORE VERIFIED: concord-<time>.tar.age"
```

The **invite code** for testers is `CONCORD_INVITE_CODE` in `.env`.

## 8. Smoke test

**(laptop)**, from a clone of the repository:
```bash
cd concord/client-core && npm ci
SMOKE_URL=https://concord-serve.duckdns.org SMOKE_INVITE=<invite code> npx vitest run test/smoke
```

It checks the following, all through the public URL:
- `/healthz` answers;
- the security headers and the CSP are present;
- sign-up without the invite code is refused;
- two users with the code edit one board at once over `wss://` and converge on the same state hash;
- the time from one client's edit to the other client seeing it (p50, p95), to report against
  the 200 ms target.

Afterwards it deletes both test accounts, and with them the board. It ends with
`smoke: …, both on <hash>…` and `1 passed`. Run it from India for the latency the testers will see.

## 9. Monitoring

- **Uptime, by email (free):**
  1. Sign up at https://uptimerobot.com, then **Add New Monitor**.
  2. Type **HTTP(s)**, URL `https://concord-serve.duckdns.org/healthz`, interval 5 minutes.
  3. Alert contact: your email.

  `/healthz` fails if the gateway is down, if Postgres doesn't answer, or if less than 10% of
  the disk is free, so all three reach your inbox.
- **Backups:** the healthchecks.io check from step 6.
- **A daily summary:** errors, fence rejections, Unsent edits reported in bug reports, memory per
  container and per JVM heap, disk, and the last backup.
  ```bash
  ~/concord/deploy/prod/summary.sh                       # now
  ( crontab -l 2>/dev/null; echo '0 7 * * * ~/concord/deploy/prod/summary.sh >> ~/concord-summary.log 2>&1' ) | crontab -
  ```
- **Money:** the zero-spend budget's emails, and the weekly look at **Credits** (section 1).

## 10. Day to day

| Task | How |
|---|---|
| Deploy a new version | Wait until that commit's CI run is green, including "publish images". Then `cd ~/concord && git pull && deploy/prod/deploy.sh <full SHA>`: a backup with the running version, pull, migrations, then the Session instances one at a time and the gateway. Clients reconnect on their own and lose nothing. It ends by printing the rollback command |
| Roll back | `deploy/prod/deploy.sh <previous SHA>`, the command the last deploy printed: the same steps, back to the old images. Migrations are forward-only and must keep the previous version working (add, never rename or drop), so the older code runs on the newer schema. Find older SHAs with `git log --oneline` or under GitHub → Actions |
| Logs | `cd ~/concord/deploy/prod && docker compose logs -f --since 1h gateway` (JSON lines; also `session1`, `session2`, `caddy`, `backup`). Docker keeps 5 × 10 MB per container |
| Memory | `free -m`, `docker stats --no-stream`, and per JVM `docker compose exec gateway jcmd 1 GC.heap_info` (also `session1`, `session2`). Limits and what is normal: section 11 |
| A backup now | `docker compose exec backup /app/backup/backup.sh` |
| Check a backup restores | **(laptop)** `deploy/prod/restore.sh <age key> latest`: throwaway Postgres, row counts and every board's hash checked. Do it weekly |
| Read bug reports | `docker compose exec -T postgres psql -U concord -d concord -c "SELECT created_at, (SELECT email FROM users u WHERE u.id = r.user_id), description FROM bug_reports r ORDER BY created_at DESC LIMIT 20"`; full diagnostics: `... -c "SELECT diagnostics FROM bug_reports WHERE id = '<id>'"` |
| Delete a tester's data | `deploy/prod/delete-user.sh someone@example.com` |
| Restart one service | `docker compose restart session1`. A Session instance hands its boards over gracefully |
| Change a setting | edit `.env`, then `docker compose up -d --wait` |
| Disk | `df -h /`; `docker image prune -a -f --filter "until=72h"` removes images not used for 3 days (keep the previous version for a rollback) |
| End of the trial | delete the data (`docker compose down -v`), then **terminate** the instance (EC2 → Instances → Terminate; its volume goes with it), **release** the Elastic IP, delete any snapshots, and delete the B2 bucket within 14 days |

## 11. Memory on a 2 GB machine

`deploy/prod/compose.yaml` gives every container a hard limit, and every JVM a capped heap,
metaspace, code cache and direct memory:

| Container | Limit | JVM |
|---|---|---|
| gateway | 320 MiB | heap 128 MiB (its threads, metaspace and buffers need the rest) |
| session1, session2 | 288 MiB each | heap 160 MiB |
| postgres | 224 MiB | shared_buffers 48 MB |
| redis | 40 MiB | |
| caddy | 48 MiB | |
| backup | 128 MiB | heap 80 MiB, only while dumping |
| **Total** | **1,336 MiB** | 72.8% of the 1,834 MiB the VM reports (`free -m`), so 27% headroom even if every container sat at its limit |
| migrate | 128 MiB | only for seconds, during a deploy |

- **Normal:** measured use, and the soak with these exact limits, are in
  `deploy/soak/results.txt`. Each container should sit well under its limit.
- **When a container hits its limit,** the kernel kills it and Docker restarts it. The
  `restarts` count then goes up:
  ```bash
  docker inspect -f '{{.Name}} oomkilled={{.State.OOMKilled}} restarts={{.RestartCount}}' $(docker compose ps -aq)
  ```
  Tell me if it happens; the limits are not to be raised blindly.
- **Swap in use** (`free -m`) means something spiked. Steady swap use during the trial is not
  normal. Nothing is ever built on the VM: a no-cache build of these images took 1,072 MiB on a
  laptop, too much next to the running stack, which is why CI builds them.
- **Measured** (`deploy/soak/results.txt`, run 3, these exact limits, 36 minutes, 0 OOM kills):

  | | Exact peak (incl. reclaimable cache) | Anonymous memory |
  |---|---|---|
  | gateway | 260 MiB | 218 MiB |
  | session1 | 171 MiB | 141 MiB |
  | session2 | 195 MiB | 165 MiB |
  | postgres | 224 MiB, its file cache filling the limit; reclaimed, never killed | 18 MiB |
  | backup, during a dump | 91 MiB | |
  | migrate | 58 MiB | |

## 12. If the machine dies

What you need is all off the machine: the code (GitHub), `.env` (your password manager), the
backups (B2), and the age private key (your password manager).

1. Launch a new instance like section 2 (same security group).
2. **Move the Elastic IP:** EC2 → Elastic IPs → 52.62.82.248 → **Disassociate**, then
   **Associate** it with the new instance. DuckDNS needs no change. Don't allocate a second one.
3. Do sections 3–4 on it, then put `.env` back in `~/concord/deploy/prod/`.
4. Pull, restore the latest backup into the new database, verified, then start everything:
   ```bash
   cd ~/concord/deploy/prod
   docker compose pull
   nano /tmp/k                                 # paste the age private key; deleted below
   ./restore.sh /tmp/k latest --into-prod      # asks you to type the board count, verifies, starts
   shred -u /tmp/k
   ```
5. Check `curl https://concord-serve.duckdns.org/healthz`, then run the smoke test (section 8).
6. Terminate the dead instance, so its volume stops costing credits.

Edits made after the last backup are still in testers' browsers (IndexedDB). They are resent
when the browsers reconnect, so they come back too, as long as testers reopen the board in the
same browser.

## 13. Alternatives: Oracle, GCP

If AWS ever stops being free for this, the same stack runs elsewhere. The steps change only in
sections 2–4.
- **Oracle Cloud Always Free:**
  - An Ampere A1 VM (arm64, up to 4 OCPU and 24 GB in total), free indefinitely, with a reserved
    public IP.
  - Open 80 and 443 twice: in the VCN's security list, and in the image's own iptables rules
    (Oracle's Ubuntu images reject everything but SSH; add two `ACCEPT` rules, then
    `netfilter-persistent save`).
  - Capacity is often short in popular regions.
- **Google Cloud free tier:**
  - One e2-micro (x86, 1 GB RAM) in some US regions.
  - Too small for this stack without cutting it to one Session instance; the new-account trial
    credits run out.

## 14. Reliant

The earlier plan was Reliant on the same VM behind the same Caddy, at `reliant-<me>.duckdns.org`.
On a 2 GB t4g.small that is **not possible**: Concord's limits take 1,336 MiB of 1,834 MiB,
and Reliant needs more than what is left. Reliant needs its own instance, a larger one (which
costs credits faster), or the Oracle option above.
