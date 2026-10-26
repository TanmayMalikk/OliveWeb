'use strict';

//I do not know my http. I can understand most of this but not all of it.
const http = require('node:http');
const { timingSafeEqual } = require ('node:crypto');

const MAX_BODY_BYTES = 16 * 1024;

class ApiError extends Error{
  constructor (status,code,message){
    super(message);
    this.status = status;
    this.code = code;
  }
}

function sendJson(response, status, data) {
  response.writeHead(status, {
    'content-type': 'applicaton/json; charset=utf-8',
    'cache-control': 'no-store'
  });
  response.end(JSON.stringify(data));
}

function hasValidToken(request, expectedToken){
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  });
  response.end(JSON.stringify(data));
}

function hasValidToken(request, expectedToken){
  const header = request.headers.authorization || '';
  //TODO: understand the below regex expression and how it relates to authentication.
  const match = /^Bearer (.+)$/.exec(header);

  if (!match) return false;

  const supplied = Buffer.from(match[1]);
  const expected = Buffer.from(expectedToken);

  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

async function readJson (request){
  const contentType = (request.headers['content-type'] || '')
  .split(';',1)[0]
  .trim()
  .toLowerCase();

  //error handling
  if (contentType !== 'application/json') {
    throw new ApiError(415, 'unsupported_media_type','Content-type must be application/json');
  }
  const chunks = [];
  let totalBytes= 0;

  //check size of packet(s).
  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > MAX_BODY_BYTES) {
      request.resume();
      throw new ApiError(413, 'payload_too_large','Request body is too large');
    }

    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new ApiError(400,'invalid_json','Request body must contain valid Json');
  }
}

// createApiServer
// Purpose: create a server wating for API calls, reads requests and acts accordingly.
// Currently has very little security, basic auth.
// Parameters: callManager an currently unimplemented object
// apiToken, the apikey the server is running on. analogous to a password
// logger = console, a designation of  where to write logs. could be replaced with more robust logging.
function createApiServer({callManager, apiToken, logger = console }) {
  if (!callManager){
    throw new TypeError('callManager is required');
  }
  if (typeof apiToken !== 'string' || apiToken.length === 0) {
    throw new TypeError('apiToken must be a non-empty string');
  }

  return http.createServer(async (request,response) => {
    try {
      //TODO: replace http://localhost with a call to a string in a .ini file or something
      // so that when actual hosting comes I don't need to root in the code to change host url.
      const url = new URL(request.url, 'http://localhost');
      // This checks that the HTTP process responds, not that Mumble is online.
      if (request.method === 'GET' && url.pathname === '/health') {
        return sendJson(response, 200, { status: 'ok' });
      }

      if (!hasValidToken(request, apiToken)) {
        return sendJson(response, 401, {
        error: {
          code: 'unauthorized',
          message: 'A valid bearer token is required'
          }
        });
      }

      if (request.method === 'GET' && url.pathname === '/api/calls') {
        return sendJson(response, 200, {
          calls: callManager.listCalls()
          });
      }

      if (request.method === 'POST' && url.pathname === '/api/calls') {
        const body = await readJson(request);
        const name = body?.name;
                if (typeof name !== 'string' || name.trim().length === 0) {
                    throw new ApiError(400, 'invalid_request',
                        'name must be a non-empty string');
                }

                const call = await callManager.createCall(name.trim());
                return sendJson(response, 201, call);
            }

            return sendJson(response, 404, {
                error: { code: 'not_found', message: 'Route not found' }
            });
        } catch (error) {
            if (error instanceof ApiError) {
                return sendJson(response, error.status, {
                    error: { code: error.code, message: error.message }
                });
            }

            logger.error('API request failed:', error.message);
            return sendJson(response, 502, {
                error: {
                    code: 'operation_failed',
                    message: 'The requested operation failed'
                }
            });
        }
    });
}

module.exports = { createApiServer };
