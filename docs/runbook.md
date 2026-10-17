# Concord runbook: from a fresh machine to the trial, and running it

Everything here costs nothing: an Oracle Cloud **Always Free** Ampere (ARM64) VM, a free
**DuckDNS** hostname, **Let's Encrypt** certificates via Caddy, and backups to **Backblaze B2**
(free 10 GB) or **Oracle Object Storage** (Always Free 20 GB). Commands in `code` run on the VM
unless marked **(laptop)**.

Contents:
1. [Create the VM](#1-create-the-vm)
2. [Open ports 80 and 443](#2-open-ports-80-and-443)
3. [The DuckDNS hostname](#3-the-duckdns-hostname)
4. [Docker and the code](#4-docker-and-the-code)
5. [The backup key and bucket](#5-the-backup-key-and-bucket)
6. [Settings](#6-settings)
7. [Start](#7-start)
8. [Smoke test](#8-smoke-test)
9. [Monitoring](#9-monitoring)
10. [Day to day](#10-day-to-day)
11. [If the machine dies](#11-if-the-machine-dies)
12. [Adding Reliant later](#12-adding-reliant-later)

## 1. Create the VM

1. Sign in to cloud.oracle.com, then **Compute → Instances → Create instance**.
2. **Image:** Canonical Ubuntu 24.04. **Shape:** Ampere `VM.Standard.A1.Flex` (Always Free eligible).
   - Concord alone needs at most about 3 GB.
   - Pick **2 OCPU and 12 GB**, so Reliant fits later (section 12). The Always Free limit is
     4 OCPU and 24 GB in total.
   - If Oracle says "out of capacity", try another availability domain, or try again later.
3. **Networking:** keep the new VCN and public subnet, and **assign a public IPv4 address**.
4. **SSH keys:** upload your public key (or let Oracle generate one and save it).
5. **Boot volume:** the default 47 GB is plenty.
6. Create. Note the **public IP**.
7. Make the IP permanent, so DuckDNS never points at a stale address:
   - open the instance's **Attached VNICs → the VNIC → IPv4 Addresses → Edit**;
   - choose **Reserved public IP → Create new** (free).
8. **(laptop)** `ssh ubuntu@<public IP>`

## 2. Open ports 80 and 443

Two firewalls must both allow them: Oracle's (outside the VM) and the VM's own. SSH (22) is
already open; open nothing else.

**Oracle's security list:**
1. Go to **Networking → Virtual cloud networks → your VCN → Security Lists → Default Security List**.
2. **Add Ingress Rules**, twice:
   - Source CIDR `0.0.0.0/0`, IP protocol TCP, destination port `80`, description "Caddy HTTP (Let's Encrypt)";
   - Source CIDR `0.0.0.0/0`, IP protocol TCP, destination port `443`, description "Caddy HTTPS".
3. Optional but good: change the existing port-22 rule's source from `0.0.0.0/0` to your
   home IP followed by `/32`.

**The VM's own firewall.** Oracle's Ubuntu images ship iptables rules that reject everything but SSH:
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
sudo iptables -L INPUT -n --line-numbers | head -12   # 80 and 443 ACCEPT, above the REJECT line
```

## 3. The DuckDNS hostname

1. Go to https://www.duckdns.org and sign in (GitHub or Google).
2. Under **sub domain**, type `concord-<me>` and press **add domain**.
3. In its **current ip** box, type the VM's public IP and press **update ip**.
4. Copy your **token** from the top of the page; you need it for step 4.
5. **(laptop)** Check it: `nslookup concord-<me>.duckdns.org` must answer with the VM's IP.

The IP is reserved, so it should never change. In case it does, a cron line keeps DuckDNS
current (step 4).

## 4. Docker and the code

```bash
sudo apt-get update && sudo apt-get -y upgrade
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit        # log out and in again, so the group applies
docker run --rm hello-world                    # check
git clone https://github.com/sohans1092004/concord.git ~/concord
# Keep DuckDNS pointed at this VM (every 5 minutes; harmless when nothing changed):
( crontab -l 2>/dev/null; echo '*/5 * * * * curl -fsS "https://www.duckdns.org/update?domains=concord-<me>&token=<token>&ip=" >/dev/null' ) | crontab -
```

The images are built on the VM itself, so they come out ARM64. Every base image is multi-arch.

## 5. The backup key and bucket

**The age key** encrypts the backups. Only its *public* half goes on the VM.
1. **(laptop)** Generate it, using the backup image so there's nothing to install. On the
   VM, after step 7's build:
   ```bash
   docker run --rm --entrypoint age-keygen concord-backup:prod
   ```
   Or on the laptop, with `age` installed (`winget install FiloSottile.age`): `age-keygen -o concord-backup.key`.
2. It prints a private key (`AGE-SECRET-KEY-1…`) and a public key (`age1…`).
   - **Save the private key** in your password manager, plus one offline copy. Without it no
     backup can be restored.
   - If you generated it on the VM, do not leave it there.
3. The public key is `AGE_RECIPIENT` in step 6.

**The bucket. Choose one.**

**(a) Backblaze B2.** The free 10 GB says it needs no card; if sign-up asks for one, use (b).
1. Sign up at https://www.backblaze.com/sign-up/cloud-storage.
2. Go to **B2 Cloud Storage → Buckets → Create a Bucket**:
   - name `concord-backups-<me>`;
   - Files: **Private**;
   - encryption: off (the backups are already encrypted);
   - object lock: off.
3. Set the bucket's **Lifecycle Settings** to **Keep only the last version of the file**.
4. Go to **Application Keys → Add a New Application Key**:
   - name `concord-vm`;
   - allow access to the bucket `concord-backups-<me>` only;
   - type **Read and Write**.
5. Copy the **keyID** and **applicationKey**. The key is shown only once.

**(b) Oracle Object Storage.**
1. Go to **Storage → Buckets → Create Bucket**: name `concord-backups`, Standard tier, private.
2. Find your **namespace** under **Profile → Tenancy**, and note the region (e.g. `ap-mumbai-1`).
3. Go to **Profile → My profile → Customer secret keys → Generate secret key**, named
   `concord-backup`. Copy the secret (shown once) and the **Access key**.
4. The bucket is in the same account as the VM, so also keep a copy on your laptop, weekly,
   with `deploy/prod/pull-backups.sh` (see its header for setup).

## 6. Settings

```bash
cd ~/concord/deploy/prod
./gen-env.sh concord-<me>.duckdns.org you@example.com    # writes .env with fresh random secrets
nano .env
```

In `.env`:
1. Set `AGE_RECIPIENT=age1…`, the public key from step 5.
2. Uncomment and fill **one** storage block, (a) B2 or (b) Oracle, following the comments.
   Delete the two `CHANGE_ME` lines.
3. Optional: set `BACKUP_PING_URL`.
   - Create a free check at https://healthchecks.io, period 1 day, grace 2 hours.
   - Paste its ping URL. It emails you if a night's backup never arrives.

**Keep a copy of `.env` in your password manager.** It holds the database password and the
invite code. `.env` is never committed (`.gitignore`), and production refuses to start with any
development value (`Config.checkPublic`).

## 7. Start

```bash
cd ~/concord/deploy/prod
docker compose up -d --build --wait      # the first build takes several minutes on ARM
docker compose ps                         # every service "healthy" or "running"; migrate "exited (0)"
docker compose logs caddy | grep -i certificate   # "certificate obtained successfully"
curl -fsS https://concord-<me>.duckdns.org/healthz   # ok
docker compose exec backup /app/backup/backup.sh     # a first backup now; prints backup_done
```

Then prove the backup restores. **(laptop)**, from a clone with the same `.env` copied over:
```bash
cd concord/deploy/prod && docker compose build backup gateway
./restore.sh ~/concord-backup.key latest     # ends "RESTORE VERIFIED: concord-<time>.tar.age"
```

The **invite code** for testers is `CONCORD_INVITE_CODE` in `.env`.

## 8. Smoke test

**(laptop)**, from a clone of the repository:
```bash
cd concord/client-core && npm ci
SMOKE_URL=https://concord-<me>.duckdns.org SMOKE_INVITE=<invite code> npx vitest run test/smoke
```

It checks the following, all through the public URL:
- `/healthz` answers;
- the security headers and the CSP are present;
- sign-up without the invite code is refused;
- two users with the code edit one board at once over `wss://` and converge on the same state hash.

Afterwards it deletes both test accounts, and with them the board. It ends with
`smoke: …, both on <hash>…` and `1 passed`.

## 9. Monitoring

- **Uptime, by email (free):**
  1. Sign up at https://uptimerobot.com, then **Add New Monitor**.
  2. Type **HTTP(s)**, URL `https://concord-<me>.duckdns.org/healthz`, interval 5 minutes.
  3. Alert contact: your email.

  `/healthz` fails if the gateway is down, if Postgres doesn't answer, or if less than 10% of
  the disk is free, so all three reach your inbox.
- **Backups:** the healthchecks.io check from step 6.
- **A daily summary:** errors, fence rejections, Unsent edits reported in bug reports, memory per
  container and per JVM heap, disk, and the last backup.
  ```bash
  ~/concord/deploy/prod/summary.sh                       # now
  ( crontab -l; echo '0 7 * * * ~/concord/deploy/prod/summary.sh >> ~/concord-summary.log 2>&1' ) | crontab -
  ```

## 10. Day to day

| Task | How |
|---|---|
| Deploy a new version | `cd ~/concord && git pull && deploy/prod/deploy.sh`: backup, migrations, then the Session instances one at a time and the gateway; clients reconnect on their own and lose nothing |
| Logs | `cd ~/concord/deploy/prod && docker compose logs -f --since 1h gateway` (JSON lines; also `session1`, `session2`, `caddy`, `backup`). Docker keeps 5 × 10 MB per container |
| Memory | `docker stats --no-stream`; per JVM: `docker compose exec gateway jcmd 1 GC.heap_info` (also `session1`, `session2`). Limits: gateway 512 MB (heap 256 MB), each Session 640 MB (heap 384 MB), Postgres 384 MB, Redis 96 MB, Caddy 96 MB, backup 384 MB. `free -m` should show plenty available |
| A backup now | `docker compose exec backup /app/backup/backup.sh` |
| Check a backup restores | **(laptop)** `deploy/prod/restore.sh <age key> latest`: throwaway Postgres, row counts and every board's hash checked. Do it weekly |
| Read bug reports | `docker compose exec -T postgres psql -U concord -d concord -c "SELECT created_at, (SELECT email FROM users u WHERE u.id = r.user_id), description FROM bug_reports r ORDER BY created_at DESC LIMIT 20"`; full diagnostics: `... -c "SELECT diagnostics FROM bug_reports WHERE id = '<id>'"` |
| Delete a tester's data | `deploy/prod/delete-user.sh someone@example.com` |
| Restart one service | `docker compose restart session1`. A Session instance hands its boards over gracefully |
| Change a setting | edit `.env`, then `docker compose up -d --wait` |
| Disk | `df -h /`; `docker system prune -f` removes old images after deploys |

## 11. If the machine dies

What you need is all off the machine: the code (GitHub), `.env` (your password manager), the
backups (the bucket, and the laptop copies), and the age private key (your password manager).

1. Create a new VM: sections 1 and 2. Keep the reserved IP if you can (move it to the new VM's
   VNIC); otherwise point DuckDNS at the new IP (section 3).
2. Install Docker and clone the code (section 4).
3. Put `.env` back in `~/concord/deploy/prod/` (from your password manager).
4. Build, start the database, restore the latest backup into it, verified, then start everything:
   ```bash
   cd ~/concord/deploy/prod
   docker compose build
   scp you@laptop:concord-backup.key /tmp/k   # or paste it into /tmp/k; delete it afterwards
   ./restore.sh /tmp/k latest --into-prod     # asks you to type the board count, verifies, starts
   shred -u /tmp/k
   ```
5. Check: `curl https://concord-<me>.duckdns.org/healthz`, then run the smoke test (section 8).

Edits made after the last backup are still in testers' browsers (IndexedDB). They are resent
when the browsers reconnect, so they come back too, as long as testers reopen the board in the
same browser.

## 12. Adding Reliant later

DuckDNS allows 5 subdomains: add `reliant-<me>` with the same IP.
- **Caddy:** add a site block for `reliant-<me>.duckdns.org` to `deploy/prod/Caddyfile`, proxying
  to Reliant's dashboard on a shared Docker network.
- **Reliant's own deploy:** drop its Caddy and use this one.
- **Memory:** Concord's limits add up to about 2.8 GB. On a 12 GB VM that leaves about 9 GB
  for Reliant and the OS; Reliant's README suggests 4 GB for a demo.

Not built yet.
