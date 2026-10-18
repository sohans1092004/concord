recovery-tails-missed.txt: the first recovery run. Its harness read the board version from Postgres
on every rename, so the 499-op tail took over 60s and the owner's 60s snapshot timer fired first:
the tails were 7 and 2 ops, not 499. Superseded by raw/recovery.txt (a counter instead of the read).
