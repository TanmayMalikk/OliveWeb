'use strict';

const { applicationDefault, getApps, initializeApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getDatabase } = require('firebase-admin/database');
const { getMessaging } = require('firebase-admin/messaging');
const { FirebaseCallStore } = require('./calls');

const INVALID_TOKEN_CODES = new Set([
    'auth/argument-error',
    'auth/id-token-expired',
    'auth/id-token-revoked',
    'auth/invalid-id-token'
]);

function createFirebaseAdapters({
    database,
    auth,
    messaging,
    callsPath,
    usersPath
}) {
    if (!database || !auth || !messaging) {
        throw new TypeError('database, auth, and messaging are required');
    }

    const callStore = new FirebaseCallStore(database, { callsPath, usersPath });
    return {
        callStore,
        async verifyIdToken(token) {
            try {
                return await auth.verifyIdToken(token, true);
            } catch (error) {
                if (INVALID_TOKEN_CODES.has(error.code)) return null;
                throw error;
            }
        },
        async verifyUserIds(userIds) {
            const results = await Promise.all(userIds.map(async (uid) => {
                try {
                    await auth.getUser(uid);
                    return true;
                } catch (error) {
                    if (error.code === 'auth/user-not-found') return false;
                    throw error;
                }
            }));
            return results.every(Boolean);
        },
        async sendInvitation({ recipientId, callId, callerId, callName }) {
            const tokens = await callStore.getFcmTokens(recipientId);
            if (tokens.length === 0) {
                const error = new Error('Recipient has no registered FCM tokens');
                error.code = 'no_fcm_tokens';
                throw error;
            }

            const result = await messaging.sendEachForMulticast({
                tokens,
                data: {
                    type: 'call_invitation',
                    callId,
                    callerId,
                    recipientId,
                    callName: callName || ''
                }
            });
            if (result.successCount === 0) {
                const error = new Error('Firebase could not deliver the call invitation');
                error.code = 'notification_delivery_failed';
                throw error;
            }
            return { sent: result.successCount, failed: result.failureCount };
        }
    };
}

function initializeFirebaseAdapters({
    databaseUrl = process.env.FIREBASE_DATABASE_URL,
    callsPath = process.env.FIREBASE_CALLS_PATH || 'calls',
    usersPath = process.env.FIREBASE_USERS_PATH || 'users'
} = {}) {
    if (!databaseUrl) throw new Error('Set FIREBASE_DATABASE_URL before starting the API');
    const app = getApps()[0] || initializeApp({
        credential: applicationDefault(),
        databaseURL: databaseUrl
    });

    return createFirebaseAdapters({
        database: getDatabase(app),
        auth: getAuth(app),
        messaging: getMessaging(app),
        callsPath,
        usersPath
    });
}

module.exports = { createFirebaseAdapters, initializeFirebaseAdapters };
