# Off-host backup job for the production stack (docs/runbooks/restore.md). Build context: deploy/compose/backup.
# Based on the Postgres image so that pg_dump matches the server's major version (18).
FROM postgres:18.6-alpine

# rclone moves the data off-host. The vault CLI comes from the pinned Vault image, the same version as the server.
# User backup (uid 10001) runs the jobs. It is in group postgres (70) to read the WAL spool that postgres writes.
# /run/backup is a setgid directory owned by root:backup, so the env file that root writes at start is group-readable.
RUN apk add --no-cache rclone \
 && addgroup -g 10001 -S backup \
 && adduser -D -u 10001 -G backup -h /var/lib/backup -s /sbin/nologin backup \
 && addgroup backup postgres \
 && rm -f /var/spool/cron/crontabs/root \
 && mkdir -p /var/lib/backup/work /run/backup \
 && chown -R backup:backup /var/lib/backup \
 && chown root:backup /run/backup \
 && chmod 2750 /run/backup
COPY --from=hashicorp/vault:2.2.1 /bin/vault /usr/local/bin/vault
COPY backup.sh /usr/local/bin/backup.sh
COPY crontab /var/spool/cron/crontabs/backup
RUN chmod 0755 /usr/local/bin/backup.sh /usr/local/bin/vault \
 && chmod 0600 /var/spool/cron/crontabs/backup

ENTRYPOINT ["/usr/local/bin/backup.sh"]
CMD ["start"]
