import { execFileSync } from 'node:child_process';

/** The Postgres container of the e2e stack (deploy/compose/e2e.sh, compose project emitkit-e2e). */
const CONTAINER = 'emitkit-e2e-postgres-1';

/** The ULID form every row id in the stack uses. Checked before an id is put into SQL. */
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;

interface Connection {
  user: string;
  database: string;
}
let connection: Connection | undefined;

/**
 * Runs docker with a fixed argument array (no shell). A failure is rethrown with the exit status only: docker's own
 * message repeats the arguments, and SQL sent on stdin can hold a secret value that must never reach a failure message.
 */
function docker(args: string[], input?: string): string {
  try {
    return execFileSync('docker', args, { input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], timeout: 30_000 }).trim();
  } catch (error) {
    const status = (error as { status?: number | null }).status;
    // eslint-disable-next-line preserve-caught-error -- the cause can quote stdin SQL that holds a secret
    throw new Error(`docker ${args[0]} failed (exit ${status ?? 'unknown'}) against the e2e Postgres container`);
  }
}

function connect(): Connection {
  if (!connection) {
    connection = {
      user: docker(['exec', CONTAINER, 'printenv', 'POSTGRES_USER']),
      database: docker(['exec', CONTAINER, 'printenv', 'POSTGRES_DB']),
    };
  }
  return connection;
}

/**
 * Runs one read-only SQL statement in the stack's database and returns its rows, each as its columns. The SQL goes in on
 * stdin, not in the argument list, so it never shows in a process listing. Callers select booleans and counts, never raw
 * secret columns, so nothing a failure prints can carry a stored value.
 */
export function queryRows(sql: string): string[][] {
  const { user, database } = connect();
  const out = docker(['exec', '-i', CONTAINER, 'psql', '-U', user, '-d', database, '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1'], sql);
  return out
    .split('\n')
    .filter((line) => line.length > 0)
    .map((line) => line.split('|'));
}

/** Returns the id unchanged when it is a ULID, so it can be put into SQL; anything else is refused. */
export function ulid(id: string): string {
  if (!ULID.test(id)) throw new Error('expected a ULID id');
  return id;
}

/** A SQL string literal for a value that is not an id: quotes are doubled, so the value stays data. */
export function sqlString(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}
