'use strict';

// Hostinger chooses PORT for deployed apps. VOIP_API_PORT remains useful when running locally.
function configuredPort(environment = process.env) {
    const port = Number(environment.PORT || environment.VOIP_API_PORT || 3000);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error('PORT or VOIP_API_PORT must be a valid port number');
    }
    return port;
}

module.exports = { configuredPort };
