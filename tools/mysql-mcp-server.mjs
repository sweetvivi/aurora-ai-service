#!/usr/bin/env node
/**
 * Local Docker MySQL MCP server (stdio, JSON-RPC 2.0).
 *
 * Talks to the MySQL running in a local Docker container, NOT over SSH. Every statement is
 * executed by piping it into the container's own `mysql` client:
 *
 *     docker exec -i <container> sh -lc 'MYSQL_PWD=... mysql -u<user> ...'
 *
 * The password is passed through `MYSQL_PWD` inside the container, so it never appears on the
 * host command line and the SQL travels on stdin rather than as an argument.
 *
 * Unlike the remote variant this server allows DDL and DML: it exists for local integration
 * testing, where creating the schema, seeding rows and truncating tables between runs is the
 * point. The account it uses is a throwaway local test account with full privileges; do not
 * point it at a database that matters.
 *
 * Configuration (environment, all optional):
 *
 *     MYSQL_MCP_CONTAINER   container name          default niren-mysql
 *     MYSQL_MCP_USER        login                   default backend
 *     MYSQL_MCP_PASSWORD    password                default backend_dev_2026
 *     MYSQL_MCP_DATABASE    default database        default backend
 *     MYSQL_MCP_MAX_ROWS    result row cap          default 500
 *     MYSQL_MCP_TIMEOUT     seconds per statement   default 60
 */

import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const PROTOCOL_VERSION = '2025-06-18';
const SERVER_INFO = Object.freeze({ name: 'mysql-local-docker-mcp', version: '0.1.0' });
const SYSTEM_DATABASES = ['information_schema', 'mysql', 'performance_schema', 'sys'];
const IDENTIFIER_PATTERN = /^[A-Za-z0-9_$]+$/;
const OUTPUT_LIMIT_BYTES = 192 * 1024;

function readConfig(env = process.env) {
    return {
        container: env.MYSQL_MCP_CONTAINER || 'niren-mysql',
        user: env.MYSQL_MCP_USER || 'backend',
        password: env.MYSQL_MCP_PASSWORD || 'backend_dev_2026',
        defaultDatabase: env.MYSQL_MCP_DATABASE || 'backend',
        maxRows: positiveInt(env.MYSQL_MCP_MAX_ROWS, 500),
        timeoutSeconds: positiveInt(env.MYSQL_MCP_TIMEOUT, 60),
    };
}

function positiveInt(value, fallback) {
    const parsed = Number.parseInt(value ?? '', 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function shellQuote(value) {
    return `'${String(value).replaceAll("'", "'\"'\"'")}'`;
}

export function isSafeIdentifier(value) {
    return IDENTIFIER_PATTERN.test(String(value || ''));
}

function sqlStringLiteral(value) {
    return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''")}'`;
}

/**
 * Builds the argv handed to `docker`. The SQL itself is supplied on stdin by the caller, so it
 * is never interpolated into the shell line.
 */
export function buildDockerArgs(config, database = '') {
    const client = [
        'MYSQL_PWD="$MYSQL_MCP_PASSWORD"',
        'mysql',
        `-u${shellQuote(config.user)}`,
        '--batch',
        '--raw',
        // Windows console defaults to a single-byte code page; without this the server receives
        // UTF-8 bytes as latin1 and every Chinese string is stored double-encoded.
        '--default-character-set=utf8mb4',
    ];
    if (database) {
        client.push(`--database=${shellQuote(database)}`);
    }
    return [
        'exec', '-i',
        '-e', 'MYSQL_MCP_PASSWORD',
        config.container,
        'sh', '-lc', client.join(' '),
    ];
}

function runMysql(config, sql, { database = '', timeoutSeconds } = {}) {
    return new Promise((resolve) => {
        const child = execFile(
            'docker',
            buildDockerArgs(config, database),
            {
                timeout: Math.max(1, Math.min(timeoutSeconds || config.timeoutSeconds, 600)) * 1000,
                windowsHide: true,
                maxBuffer: 8 * 1024 * 1024,
                env: { ...process.env, MYSQL_MCP_PASSWORD: config.password },
            },
            (error, stdout, stderr) => {
                resolve({
                    stdout: stdout ?? '',
                    stderr: stripPasswordWarning(stderr ?? ''),
                    exitCode: error?.code ?? 0,
                    timedOut: Boolean(error?.killed),
                });
            },
        );
        child.stdin.end(sql.endsWith(';') ? sql : `${sql};`);
    });
}

function stripPasswordWarning(stderr) {
    return stderr
        .split(/\r?\n/)
        .filter((line) => !line.includes('[Warning] Using a password on the command line'))
        .join('\n')
        .trim();
}

function textResult(payload, { isError = false } = {}) {
    const text = [
        payload.stdout,
        payload.stderr ? `stderr:\n${payload.stderr}` : '',
    ].filter(Boolean).join('\n').trim() || '(no output)';

    const clipped = text.length > OUTPUT_LIMIT_BYTES
        ? `${text.slice(0, OUTPUT_LIMIT_BYTES)}\n[output truncated at ${OUTPUT_LIMIT_BYTES} bytes]`
        : text;

    return {
        content: [{ type: 'text', text: clipped }],
        structuredContent: { ...payload, stdout: text },
        ...(isError ? { isError: true } : {}),
    };
}

function errorResult(message, exitCode = 2) {
    return textResult({ stdout: '', stderr: message, exitCode, timedOut: false }, { isError: true });
}

function toolDefinitions() {
    return [
        {
            name: 'mysql_ping',
            description: 'Check the local Dockerized MySQL connection and return its version and login.',
            inputSchema: { type: 'object', properties: {}, additionalProperties: false },
        },
        {
            name: 'mysql_databases',
            description: 'List MySQL databases. System databases are hidden unless includeSystem is true.',
            inputSchema: {
                type: 'object',
                properties: { includeSystem: { type: 'boolean' } },
                additionalProperties: false,
            },
        },
        {
            name: 'mysql_tables',
            description: 'List tables and views in a database, with their approximate row counts.',
            inputSchema: {
                type: 'object',
                properties: {
                    database: { type: 'string', pattern: '^[A-Za-z0-9_$]+$' },
                },
                additionalProperties: false,
            },
        },
        {
            name: 'mysql_describe_table',
            description: 'Return columns, keys and indexes of a table.',
            inputSchema: {
                type: 'object',
                properties: {
                    database: { type: 'string', pattern: '^[A-Za-z0-9_$]+$' },
                    table: { type: 'string', pattern: '^[A-Za-z0-9_$]+$' },
                },
                required: ['table'],
                additionalProperties: false,
            },
        },
        {
            name: 'mysql_query',
            description:
                'Run SQL against the local test database. Any statement is allowed, including write, DDL and '
                + 'multiple statements separated by semicolons. Returns tab-separated output with a header row.',
            inputSchema: {
                type: 'object',
                properties: {
                    sql: { type: 'string', minLength: 1 },
                    database: { type: 'string', pattern: '^[A-Za-z0-9_$]+$' },
                    maxRows: { type: 'integer', minimum: 1, maximum: 5000 },
                },
                required: ['sql'],
                additionalProperties: false,
            },
        },
        {
            name: 'mysql_exec',
            description:
                'Run a write/DDL script (INSERT, UPDATE, DELETE, CREATE, ALTER, TRUNCATE, CALL, multi-statement). '
                + 'Semantically identical to mysql_query but states its intent, so an agent does not have to '
                + 'lie about being read-only.',
            inputSchema: {
                type: 'object',
                properties: {
                    sql: { type: 'string', minLength: 1 },
                    database: { type: 'string', pattern: '^[A-Za-z0-9_$]+$' },
                },
                required: ['sql'],
                additionalProperties: false,
            },
        },
    ];
}

/**
 * Caps the result at {@code maxRows} DATA rows. The first line of batch-mode output is the column
 * header, so it is never counted against the cap — counting it made {@code maxRows: 1} return the
 * header and drop the single row the caller asked for.
 */
function limitRows(payload, maxRows) {
    if (!payload.stdout || !maxRows) return payload;
    const rows = payload.stdout.split(/\r?\n/);
    if (rows.at(-1) === '') rows.pop();
    const header = rows.slice(0, 1);
    const data = rows.slice(1);
    if (data.length <= maxRows) return payload;
    return {
        ...payload,
        stdout: `${[...header, ...data.slice(0, maxRows)].join('\n')}\n[truncated after ${maxRows} rows]\n`,
    };
}

export function createMysqlMcpServer({ config = readConfig(), runner = runMysql } = {}) {
    // One statement at a time, so a test that truncates and seeds cannot interleave with another.
    let queryQueue = Promise.resolve();

    function runQuery(sql, options = {}) {
        const execute = async () => limitRows(await runner(config, sql, options), options.maxRows || config.maxRows);
        const next = queryQueue.then(execute, execute);
        queryQueue = next.catch(() => undefined);
        return next;
    }

    function resolveDatabase(value) {
        const database = String(value || config.defaultDatabase || '').trim();
        return isSafeIdentifier(database) ? database : '';
    }

    async function callTool(name, input = {}) {
        if (name === 'mysql_ping') {
            const payload = await runQuery('SELECT VERSION(), CURRENT_USER(), @@port', { maxRows: 1 });
            return textResult(payload, { isError: payload.exitCode !== 0 });
        }

        if (name === 'mysql_databases') {
            const whereClause = input.includeSystem === true
                ? ''
                : ` WHERE SCHEMA_NAME NOT IN (${SYSTEM_DATABASES.map(sqlStringLiteral).join(', ')})`;
            const payload = await runQuery(
                `SELECT SCHEMA_NAME FROM information_schema.SCHEMATA${whereClause} ORDER BY SCHEMA_NAME`,
                { maxRows: 1000 },
            );
            return textResult(payload, { isError: payload.exitCode !== 0 });
        }

        if (name === 'mysql_tables') {
            const database = resolveDatabase(input.database);
            if (!database) return errorResult('Invalid database identifier');
            const payload = await runQuery(
                'SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS FROM information_schema.TABLES '
                    + `WHERE TABLE_SCHEMA = ${sqlStringLiteral(database)} ORDER BY TABLE_NAME`,
                { maxRows: 1000 },
            );
            return textResult(payload, { isError: payload.exitCode !== 0 });
        }

        if (name === 'mysql_describe_table') {
            const database = resolveDatabase(input.database);
            const table = String(input.table || '').trim();
            if (!database) return errorResult('Invalid database identifier');
            if (!isSafeIdentifier(table)) return errorResult('Invalid table identifier');
            const columns = await runQuery(
                'SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, EXTRA '
                    + `FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ${sqlStringLiteral(database)} `
                    + `AND TABLE_NAME = ${sqlStringLiteral(table)} ORDER BY ORDINAL_POSITION`,
                { maxRows: 1000 },
            );
            const indexes = await runQuery(`SHOW INDEX FROM \`${table}\``, { database, maxRows: 1000 });
            return textResult({
                stdout: `${columns.stdout}\n\n-- indexes --\n${indexes.stdout}`,
                stderr: [columns.stderr, indexes.stderr].filter(Boolean).join('\n'),
                exitCode: columns.exitCode || indexes.exitCode,
                timedOut: columns.timedOut || indexes.timedOut,
            }, { isError: columns.exitCode !== 0 || indexes.exitCode !== 0 });
        }

        if (name === 'mysql_query' || name === 'mysql_exec') {
            const sql = String(input.sql || '').trim();
            if (!sql) return errorResult('sql must not be empty');
            const database = input.database ? resolveDatabase(input.database) : config.defaultDatabase;
            if (input.database && !database) return errorResult('Invalid database identifier');
            const payload = await runQuery(sql, {
                database,
                maxRows: name === 'mysql_query' ? input.maxRows : 0,
            });
            return textResult(payload, { isError: payload.exitCode !== 0 });
        }

        return errorResult(`Unknown tool: ${name}`);
    }

    async function handleMessage(message) {
        const id = message.id;

        if (message.method === 'initialize') {
            return {
                jsonrpc: '2.0',
                id,
                result: {
                    protocolVersion: PROTOCOL_VERSION,
                    capabilities: { tools: {} },
                    serverInfo: SERVER_INFO,
                },
            };
        }

        if (message.method === 'notifications/initialized' || message.method === 'notifications/cancelled') {
            return null;
        }

        if (message.method === 'tools/list') {
            return { jsonrpc: '2.0', id, result: { tools: toolDefinitions() } };
        }

        if (message.method === 'tools/call') {
            return {
                jsonrpc: '2.0',
                id,
                result: await callTool(message.params?.name, message.params?.arguments || {}),
            };
        }

        if (message.method === 'ping') {
            return { jsonrpc: '2.0', id, result: {} };
        }

        return {
            jsonrpc: '2.0',
            id,
            error: { code: -32601, message: `Method not found: ${message.method}` },
        };
    }

    return { handleMessage };
}

async function runStdioServer() {
    const server = createMysqlMcpServer();
    process.stdin.setEncoding('utf8');
    let buffer = '';

    const send = (message) => {
        if (!message) return;
        process.stdout.write(`${JSON.stringify(message)}\n`);
    };

    process.stdin.on('data', async (chunk) => {
        buffer += chunk;
        let newlineIndex = buffer.indexOf('\n');
        while (newlineIndex >= 0) {
            const line = buffer.slice(0, newlineIndex).trim();
            buffer = buffer.slice(newlineIndex + 1);
            newlineIndex = buffer.indexOf('\n');
            if (!line) continue;
            try {
                send(await server.handleMessage(JSON.parse(line)));
            } catch (error) {
                send({
                    jsonrpc: '2.0',
                    id: null,
                    error: { code: -32603, message: error.message || 'Internal error' },
                });
            }
        }
    });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    runStdioServer();
}
