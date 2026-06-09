const sdk = require('node-appwrite');
const client = new sdk.Client()
    .setEndpoint('https://sgp.cloud.appwrite.io/v1')
    .setProject('6a24f3af00123cb02a39')
    .setKey('standard_39349efffcc942fb548521f4834a8c924022eb93957b9e779341e5f3afe39248f4ce774e3225a4e20fa3dd715ad40e299a6652721a21d4888bf25298194c476ce8c7e4a9bf3512916960969911befb344bab54bb4d08deffe8379122dac42fe3cc626d5ee217365be1923791e5b27377b244dbd40d5c33a4546ff5fec6da2b52');

const storage = new sdk.Storage(client);

storage.updateBucket(
    'audio_messages',
    'Audio Messages',
    ['read("any")', 'create("any")', 'delete("any")'],
    false,           // fileSecurity
    true,            // enabled
    5 * 1024 * 1024, // 5MB
    []               // allow ALL file types
).then(() => console.log('✅ Bucket updated — all file types allowed'))
 .catch(e => console.error('❌ Failed:', e.message));
