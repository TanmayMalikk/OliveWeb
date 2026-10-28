'use strict';

const { MumbleHandler } = require('./calls');
const { connectMumbleIce } = require('./ice-config');

module.exports = {
    MumbleHandler,
    connectMumbleIce
};