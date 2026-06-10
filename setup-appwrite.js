/**
 * HappyTalk Radio — Appwrite Setup Script
 * 
 * Run this ONCE to create all required Appwrite resources:
 *   - Database: happytalk_db
 *   - Collections: channel_states, presence, audio_messages
 *   - Storage Bucket: audio_messages
 * 
 * Usage:
 *   1. npm install node-appwrite
 *   2. node setup-appwrite.js
 */

const sdk = require('node-appwrite');

// ─── Config ───────────────────────────────────────────────────────────────────
const ENDPOINT   = 'https://sgp.cloud.appwrite.io/v1';
const PROJECT_ID = '6a24f3af00123cb02a39';
const API_KEY    = 'YOUR_API_KEY_HERE';

const DATABASE_ID   = 'happytalk_db';
const BUCKET_ID     = 'audio_messages';
// ─────────────────────────────────────────────────────────────────────────────

const client = new sdk.Client()
    .setEndpoint(ENDPOINT)
    .setProject(PROJECT_ID)
    .setKey(API_KEY);

const databases = new sdk.Databases(client);
const storage   = new sdk.Storage(client);

// ─── Helpers ──────────────────────────────────────────────────────────────────

function log(msg)  { console.log('  ✅ ' + msg); }
function warn(msg) { console.log('  ⚠️  ' + msg); }
function head(msg) { console.log('\n🔧 ' + msg); }

async function createOrSkip(label, fn) {
    try {
        await fn();
        log(label);
    } catch (e) {
        if (e.code === 409) {
            warn(label + ' — already exists, skipping');
        } else {
            console.error('  ❌ Failed: ' + label, e.message);
            throw e;
        }
    }
}

// ─── Main ─────────────────────────────────────────────────────────────────────

async function main() {
    console.log('\n🚀 HappyTalk Radio — Appwrite Setup\n');
    console.log('   Endpoint  : ' + ENDPOINT);
    console.log('   Project   : ' + PROJECT_ID);
    console.log('   Database  : ' + DATABASE_ID);

    // ── 1. Database ────────────────────────────────────────────────────────────
    head('Creating database...');
    await createOrSkip('Database: ' + DATABASE_ID, () =>
        databases.create(DATABASE_ID, 'HappyTalk Database')
    );

    // ── 2. channel_states ──────────────────────────────────────────────────────
    head('Creating collection: channel_states...');
    await createOrSkip('Collection: channel_states', () =>
        databases.createCollection(
            DATABASE_ID,
            'channel_states',
            'Channel States',
            ['read("any")', 'create("any")', 'update("any")', 'delete("any")']
        )
    );

    await createOrSkip('Attribute: channelName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'channel_states', 'channelName', 64, true)
    );
    await createOrSkip('Attribute: isTransmitting (boolean)', () =>
        databases.createBooleanAttribute(DATABASE_ID, 'channel_states', 'isTransmitting', false, false)
    );
    await createOrSkip('Attribute: activeSenderId (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'channel_states', 'activeSenderId', 128, false, '')
    );
    await createOrSkip('Attribute: senderName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'channel_states', 'senderName', 128, false, 'Guest')
    );
    await createOrSkip('Attribute: timestamp (integer)', () =>
        databases.createIntegerAttribute(DATABASE_ID, 'channel_states', 'timestamp', true)
    );

    // ── 3. presence ────────────────────────────────────────────────────────────
    head('Creating collection: presence...');
    await createOrSkip('Collection: presence', () =>
        databases.createCollection(
            DATABASE_ID,
            'presence',
            'Presence',
            ['read("any")', 'create("any")', 'update("any")', 'delete("any")']
        )
    );

    await createOrSkip('Attribute: channelName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'presence', 'channelName', 64, true)
    );
    await createOrSkip('Attribute: deviceId (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'presence', 'deviceId', 128, true)
    );
    await createOrSkip('Attribute: lastSeen (integer)', () =>
        databases.createIntegerAttribute(DATABASE_ID, 'presence', 'lastSeen', true)
    );
    await createOrSkip('Attribute: userName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'presence', 'userName', 128, false, 'Guest')
    );

    await createOrSkip('Index: channelName on presence', () =>
        databases.createIndex(DATABASE_ID, 'presence', 'channelName_idx', 'key', ['channelName'])
    );

    // ── 4. audio_messages ──────────────────────────────────────────────────────
    head('Creating collection: audio_messages...');
    await createOrSkip('Collection: audio_messages', () =>
        databases.createCollection(
            DATABASE_ID,
            'audio_messages',
            'Audio Messages',
            ['read("any")', 'create("any")', 'update("any")', 'delete("any")']
        )
    );

    await createOrSkip('Attribute: channelName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'audio_messages', 'channelName', 64, true)
    );
    await createOrSkip('Attribute: senderId (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'audio_messages', 'senderId', 128, true)
    );
    await createOrSkip('Attribute: audioUrl (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'audio_messages', 'audioUrl', 1024, true)
    );
    await createOrSkip('Attribute: fileId (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'audio_messages', 'fileId', 128, false, '')
    );
    await createOrSkip('Attribute: senderName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'audio_messages', 'senderName', 128, false, 'Guest')
    );
    await createOrSkip('Attribute: timestamp (integer)', () =>
        databases.createIntegerAttribute(DATABASE_ID, 'audio_messages', 'timestamp', true)
    );

    await createOrSkip('Index: channelName on audio_messages', () =>
        databases.createIndex(DATABASE_ID, 'audio_messages', 'channelName_idx', 'key', ['channelName'])
    );
    await createOrSkip('Index: timestamp on audio_messages', () =>
        databases.createIndex(DATABASE_ID, 'audio_messages', 'timestamp_idx', 'key', ['timestamp'], ['DESC'])
    );

    // ── 5. device_tokens ───────────────────────────────────────────────────────
    head('Creating collection: device_tokens...');
    await createOrSkip('Collection: device_tokens', () =>
        databases.createCollection(
            DATABASE_ID,
            'device_tokens',
            'Device Tokens',
            ['read("any")', 'create("any")', 'update("any")', 'delete("any")']
        )
    );

    await createOrSkip('Attribute: userId (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'device_tokens', 'userId', 128, true)
    );
    await createOrSkip('Attribute: fcmToken (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'device_tokens', 'fcmToken', 1024, true)
    );
    await createOrSkip('Attribute: channelName (string)', () =>
        databases.createStringAttribute(DATABASE_ID, 'device_tokens', 'channelName', 64, true)
    );

    await createOrSkip('Index: userId on device_tokens', () =>
        databases.createIndex(DATABASE_ID, 'device_tokens', 'userId_idx', 'key', ['userId'])
    );
    await createOrSkip('Index: channelName on device_tokens', () =>
        databases.createIndex(DATABASE_ID, 'device_tokens', 'channelName_idx', 'key', ['channelName'])
    );

    // ── 6. Storage Bucket ──────────────────────────────────────────────────────
    head('Creating storage bucket: audio_messages...');
    await createOrSkip('Bucket: ' + BUCKET_ID, () =>
        storage.createBucket(
            BUCKET_ID,
            'Audio Messages',
            ['read("any")', 'create("any")', 'delete("any")'],
            false,           // fileSecurity
            true,            // enabled
            5 * 1024 * 1024, // 5MB max file size
            []               // allow all file types
        )
    );

    console.log('\n\n🎉 Setup complete! Your Appwrite backend is ready.\n');
    console.log('   Database  : happytalk_db');
    console.log('   Collections: channel_states, presence, audio_messages');
    console.log('   Bucket    : audio_messages');
    console.log('\n   You can now build and run your HappyTalk Radio app!\n');
}

main().catch(err => {
    console.error('\n❌ Setup failed:', err.message);
    process.exit(1);
});
