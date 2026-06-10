import { Client, Databases, Query } from 'node-appwrite';
import admin from 'firebase-admin';

// Initialize Firebase Admin (requires FIREBASE_CONFIG env var with service account JSON)
// Or use default credentials if deployed on GCP
let firebaseInitialized = false;
try {
    admin.initializeApp();
    firebaseInitialized = true;
} catch (e) {
    if (!/already exists/u.test(e.message)) {
        console.error('Firebase initialization error', e.stack);
    }
}

export default async ({ req, res, log, error }) => {
    log('Webhook received:', req.body);
    
    if (!firebaseInitialized) {
        error('Firebase is not initialized.');
        return res.json({ success: false, error: 'Firebase not initialized' }, 500);
    }

    // Appwrite sends the document payload in the body for collection triggers
    const message = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    
    const channelName = message.channelName;
    const senderId = message.senderId;
    const senderName = message.senderName || 'Someone';

    if (!channelName) {
        return res.json({ success: false, message: 'No channelName found' }, 400);
    }

    log(`Processing new audio for channel: ${channelName} from ${senderName}`);

    // Initialize Appwrite Client using function variables
    const client = new Client()
        .setEndpoint(process.env.APPWRITE_ENDPOINT || 'https://cloud.appwrite.io/v1')
        .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID)
        .setKey(process.env.APPWRITE_API_KEY);

    const databases = new Databases(client);
    const DATABASE_ID = process.env.APPWRITE_DATABASE_ID || 'happytalk_db';

    try {
        // Query the device_tokens collection for all tokens in this channel
        const tokensResponse = await databases.listDocuments(
            DATABASE_ID,
            'device_tokens',
            [
                Query.equal('channelName', channelName)
            ]
        );

        // Filter out the person who sent the message so they don't get a push for their own audio
        const tokens = tokensResponse.documents
            .filter(doc => doc.userId !== senderId && doc.fcmToken)
            .map(doc => doc.fcmToken);

        if (tokens.length === 0) {
            log('No offline devices to wake up.');
            return res.json({ success: true, message: 'No devices to wake' });
        }

        log(`Sending FCM to ${tokens.length} devices...`);

        // We use a high-priority "data" message to wake up the Android app
        // without showing a default notification (the app handles it in the background)
        const payload = {
            data: {
                type: 'WAKE_UP',
                channelName: channelName,
                senderName: senderName,
                audioUrl: message.audioUrl || ''
            },
            tokens: tokens
        };

        const response = await admin.messaging().sendEachForMulticast(payload);
        
        log(`FCM Sent. Success: ${response.successCount}, Failures: ${response.failureCount}`);
        
        if (response.failureCount > 0) {
            response.responses.forEach((resp, idx) => {
                if (!resp.success) {
                    error(`Failed to send to token ${tokens[idx]}: ${resp.error}`);
                }
            });
        }

        return res.json({ success: true, sent: response.successCount });

    } catch (err) {
        error(`Error: ${err.message}`);
        return res.json({ success: false, error: err.message }, 500);
    }
};
