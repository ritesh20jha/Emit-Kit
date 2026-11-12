# Off-host backup job (restore.md). Based on the Postgres image so pg_dump matches the server (18).
FROM postgres:18.6-alpine

# vault CLI from the pinned Vault image. Jobs run as backup (uid 10001), which is in group postgres (70) to read the
# WAL spool. /run/backup is setgid root:backup, so the env file root writes at start is readable by the jobs.
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
