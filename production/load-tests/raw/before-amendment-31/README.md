The offline 8h and 24h (accelerated) runs before amendment 31. They found the bug: the client sent
its whole queue at once and re-sent all of it on every RETRY_LATER, so the 8h queue (1,885 ops)
took 487s to drain and the 24h queue (about 5,760 ops) still had 5,243 pending after 10 minutes
(the run failed on that timeout). Nothing was lost. See SPEC amendment 31 and incident report 021.
