import { createHash } from 'node:crypto';
import { addMapping } from './mockStubs';

/** The spec file the fixture repository holds, as the project config points at it. */
export const SPEC_PATH = 'openapi/billing.yaml';
const CONTENTS_PATH = `/repos/acme/billing-api/contents/${SPEC_PATH}`;

/**
 * Two operations, one of them with a path parameter, so the linter reports nothing it should not. No tags, so the
 * linter reports `operation-tags` on every operation.
 */
export const TWO_OPERATIONS = `openapi: 3.0.3
info:
  title: Billing API
  version: 1.0.0
paths:
  /invoices:
    get:
      operationId: listInvoices
      summary: List invoices
      responses:
        "200":
          description: The invoices.
  /invoices/{id}:
    get:
      operationId: getInvoice
      summary: Get one invoice
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: The invoice.
`;

/** TWO_OPERATIONS without getInvoice: against TWO_OPERATIONS this removes an operation, which is a breaking change. */
export const ONE_OPERATION = `openapi: 3.0.3
info:
  title: Billing API
  version: 1.0.0
paths:
  /invoices:
    get:
      operationId: listInvoices
      summary: List invoices
      responses:
        "200":
          description: The invoices.
`;

/** Only createInvoice: against ONE_OPERATION this removes listInvoices, so a second breaking change in a row. */
export const CREATE_ONLY = `openapi: 3.0.3
info:
  title: Billing API
  version: 1.0.0
paths:
  /invoices:
    post:
      operationId: createInvoice
      summary: Create an invoice
      responses:
        "201":
          description: The invoice.
`;

/** Two lint errors: a path parameter that is not declared, and a duplicate operationId. Sent to POST /api/v1/lint. */
export const LINT_ERRORS = `openapi: 3.0.3
info:
  title: Lint fixture
  version: 1.0.0
paths:
  /invoices/{id}:
    get:
      operationId: listInvoices
      tags: [invoices]
      summary: Get one invoice
      responses:
        "200":
          description: The invoice.
  /orders:
    get:
      operationId: listInvoices
      tags: [orders]
      summary: List orders
      responses:
        "200":
          description: The orders.
`;

/** The git blob sha of `content`, which is the sha GitHub reports for a file with those bytes. */
export function blobSha(content: string): string {
  return createHash('sha1').update(`blob ${Buffer.byteLength(content)}\0`).update(content).digest('hex');
}

/**
 * A temporary stub that answers the spec file's GET with `content`, so the next run reads that spec. Added through the
 * admin API and tagged e2eTemp, so the caller removes it (sweepTempStubs) even if the id is lost.
 */
export function addSpecStub(content: string): Promise<string> {
  return addMapping({
    priority: 1,
    request: { method: 'GET', urlPath: CONTENTS_PATH },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'file',
        encoding: 'base64',
        size: Buffer.byteLength(content),
        name: 'billing.yaml',
        path: SPEC_PATH,
        sha: blobSha(content),
        content: Buffer.from(content, 'utf8').toString('base64'),
        html_url: `https://github.com/acme/billing-api/blob/main/${SPEC_PATH}`,
      }),
    },
  });
}
