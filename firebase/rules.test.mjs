import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

import {
  doc, setDoc, getDoc, collection, getDocs, query, where,
  serverTimestamp, writeBatch, updateDoc, deleteDoc
} from 'firebase/firestore';

import { readFile } from 'node:fs/promises';

import { before, after, test } from 'node:test';



let env;

const profile = { username:'alice_user', fullName:'Test Person', affectedNames:'', dob:'2000-01-01', language:'English', situation:'', region:'', triggerHash:'a'.repeat(64), contacts:[] };

before(async () => {

  env = await initializeTestEnvironment({ projectId:'demo-olive-rules', firestore:{host:'127.0.0.1',port:8180,rules:await readFile(new URL('./firestore.rules', import.meta.url),'utf8')} });

});

after(async () => { await env?.cleanup(); });



test('owner can create/read a profile; another user and anonymous visitors cannot', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice_user@users.olive.invalid'}).firestore();

  const bob=env.authenticatedContext('bob-uid',{email:'bob@users.olive.invalid'}).firestore();

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),profile));

  await assertSucceeds(getDoc(doc(alice,'users/alice-uid')));

  await assertFails(getDoc(doc(bob,'users/alice-uid')));

  await assertFails(setDoc(doc(bob,'users/alice-uid'),profile));

  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'users/alice-uid')));

  await assertFails(getDocs(collection(alice,'users')));

});

test('credentials and malformed contact data cannot be stored in profiles', async () => {

  const alice=env.authenticatedContext('alice-uid',{email:'alice_user@users.olive.invalid'}).firestore();

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,password:'forbidden'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,username:'someone_else'}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend'}]}));

  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:Array(26).fill({id:'c',name:'Friend',details:'test'})}));

  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid'}]}));
  await assertSucceeds(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',safeToStay:true}]}));
  await assertFails(setDoc(doc(alice,'users/alice-uid'),{...profile,contacts:[{id:'c1',name:'Friend',details:'friend@example.invalid',safeToStay:'yes'}]}));

});


test('required profile fields and address whitelist are enforced', async () => {
  const owner=env.authenticatedContext('profile-test',{email:'alice_user@users.olive.invalid'}).firestore();
  const target=doc(owner,'users/profile-test');
  await assertFails(setDoc(target,{...profile,fullName:''}));
  await assertFails(setDoc(target,{...profile,dob:''}));
  const address={line1:'123 Test Street',line2:'',city:'Edmonton',province:'Alberta',postalCode:'T5J 0N3',country:'Canada'};
  await assertSucceeds(setDoc(target,{...profile,address}));
  await assertFails(setDoc(target,{...profile,address:{...address,cardNumber:'4242424242424242'}}));
  await assertFails(setDoc(target,{...profile,cardNumber:'4242424242424242'}));
  await assertSucceeds(setDoc(target,{...profile,role:'SAFE_CONTACT',triggerHash:''}));
  await assertFails(setDoc(target,{...profile,role:'OWNER'}));
});

test('new usernames enforce bounds while existing usernames remain usable', async () => {
  const owner=env.authenticatedContext('legacy',{email:'old@users.olive.invalid'}).firestore();
  const ref=doc(owner,'users/legacy');
  await assertFails(setDoc(ref,{...profile,username:'old'}));
  await env.withSecurityRulesDisabled(async context => {await setDoc(doc(context.firestore(),'users/legacy'),{...profile,username:'old'});});
  await assertSucceeds(setDoc(ref,{...profile,username:'old',situation:'Updated'}));
});

test('file metadata is owned, shareable for reads, and cannot be reshaped', async () => {
  const alice=env.authenticatedContext('file-alice').firestore();
  const bob=env.authenticatedContext('file-bob').firestore();
  const charlie=env.authenticatedContext('file-charlie').firestore();
  const target=doc(alice,'files/file-metadata-1');
  const metadata={owner_id:'file-alice',storage_path:'users/file-alice/media/file-metadata-1',
    file_name:'Evidence file',file_type:'image/jpeg',allowed_users:['file-alice','file-bob'],
    created_at:serverTimestamp()};
  await assertSucceeds(setDoc(target,metadata));
  await assertSucceeds(getDoc(doc(bob,'files/file-metadata-1')));
  await assertFails(getDoc(doc(charlie,'files/file-metadata-1')));
  await assertFails(setDoc(doc(bob,'files/file-2'),{...metadata,storage_path:'users/file-alice/media/file-2'}));
  await assertFails(setDoc(doc(alice,'files/wrong-path'),metadata));
  await assertFails(updateDoc(doc(bob,'files/file-metadata-1'),{allowed_users:['file-alice','file-bob','file-charlie']}));
  await assertFails(updateDoc(target,{unexpected:'not allowed'}));
  await assertSucceeds(updateDoc(target,{allowed_users:['file-alice','file-charlie']}));
  await assertSucceeds(getDoc(doc(charlie,'files/file-metadata-1')));
  await assertFails(deleteDoc(doc(bob,'files/file-metadata-1')));
  await assertSucceeds(deleteDoc(target));
});

test('friend requests become a private DM and only the sender can unsend', async () => {
  const alice=env.authenticatedContext('social-a',{email:'social_alice@users.olive.invalid'}).firestore();
  const bob=env.authenticatedContext('social-b',{email:'social_bob@users.olive.invalid'}).firestore();
  const charlie=env.authenticatedContext('social-c',{email:'social_charlie@users.olive.invalid'}).firestore();
  await assertSucceeds(setDoc(doc(alice,'messageDirectory/social_alice'),{uid:'social-a',username:'social_alice',updatedAt:serverTimestamp()}));
  await assertSucceeds(setDoc(doc(bob,'messageDirectory/social_bob'),{uid:'social-b',username:'social_bob',updatedAt:serverTimestamp()}));
  await assertSucceeds(setDoc(doc(charlie,'messageDirectory/social_charlie'),{uid:'social-c',username:'social_charlie',updatedAt:serverTimestamp()}));

  await assertSucceeds(getDoc(doc(alice,'messageDirectory/social_bob')));
  await assertFails(getDocs(query(collection(alice,'messageDirectory'),where('username','>=','social_b'))));
  const requestId='social-a--social-b';
  const friendshipId='social-a--social-b';
  const request={senderUid:'social-a',senderUsername:'social_alice',recipientUid:'social-b',
    recipientUsername:'social_bob',friendshipId,createdAt:serverTimestamp()};
  await assertSucceeds(setDoc(doc(alice,'friendRequests/'+requestId),request));
  await assertSucceeds(getDocs(query(collection(bob,'friendRequests'),where('recipientUid','==','social-b'))));
  await assertSucceeds(getDocs(query(collection(alice,'friendRequests'),where('senderUid','==','social-a'))));
  await assertFails(deleteDoc(doc(charlie,'friendRequests/'+requestId)));
  await assertSucceeds(deleteDoc(doc(alice,'friendRequests/'+requestId)));
  await assertSucceeds(setDoc(doc(alice,'friendRequests/'+requestId),request));
  await assertFails(setDoc(doc(alice,'friendRequests/'+requestId),request));
  await assertFails(setDoc(doc(alice,'friendRequests/social-a--social-a'),{...request,recipientUid:'social-a',
    recipientUsername:'social_alice',friendshipId:'social-a--social-a'}));
  await assertFails(setDoc(doc(bob,'friendRequests/social-b--social-a'),{senderUid:'social-b',
    senderUsername:'social_bob',recipientUid:'social-a',recipientUsername:'social_alice',
    friendshipId,createdAt:serverTimestamp()}));
  await assertFails(setDoc(doc(charlie,'friendships/'+friendshipId),{participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    acceptedRequestId:requestId,createdAt:serverTimestamp()}));

  const accept=writeBatch(bob);
  accept.set(doc(bob,'friendships/'+friendshipId),{participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    acceptedRequestId:requestId,createdAt:serverTimestamp()});
  accept.set(doc(bob,'friendLists/social-a/friends/'+friendshipId),{friendshipId,friendUid:'social-b',
    friendUsername:'social_bob',createdAt:serverTimestamp()});
  accept.set(doc(bob,'friendLists/social-b/friends/'+friendshipId),{friendshipId,friendUid:'social-a',
    friendUsername:'social_alice',createdAt:serverTimestamp()});
  const conversation={friendshipId,participantIds:['social-a','social-b'],
    participantUsernames:{'social-a':'social_alice','social-b':'social_bob'},
    createdAt:serverTimestamp(),updatedAt:serverTimestamp(),lastMessage:'',lastMessageId:'',lastSenderUid:''};
  accept.set(doc(bob,'conversations/'+friendshipId),conversation);
  accept.delete(doc(bob,'friendRequests/'+requestId));
  await assertSucceeds(accept.commit());
  await assertSucceeds(getDocs(collection(alice,'friendLists/social-a/friends')));
  await assertSucceeds(getDocs(collection(bob,'friendLists/social-b/friends')));
  await assertFails(getDocs(collection(charlie,'friendLists/social-a/friends')));
  await assertFails(setDoc(doc(alice,'friendRequests/'+requestId),request));

  await assertSucceeds(getDoc(doc(alice,'conversations/'+friendshipId)));
  await assertFails(getDocs(query(collection(alice,'conversations'),where('participantIds','array-contains','social-a'))));
  await assertFails(getDoc(doc(charlie,'conversations/'+friendshipId)));

  const messageRef=doc(alice,'conversations/'+friendshipId+'/messages/message-1');
  const send=writeBatch(alice);
  send.set(messageRef,{senderUid:'social-a',senderUsername:'social_alice',body:'Private hello',
    deleted:false,sentAt:serverTimestamp(),clientSentAt:Date.now()});
  send.update(doc(alice,'conversations/'+friendshipId),{lastMessage:'Private hello',lastMessageId:'message-1',
    lastSenderUid:'social-a',updatedAt:serverTimestamp()});
  await assertSucceeds(send.commit());
  await assertSucceeds(getDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-1')));
  await assertFails(getDoc(doc(charlie,'conversations/'+friendshipId+'/messages/message-1')));
  await assertFails(updateDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-1'),
    {body:'Message unsent',deleted:true,deletedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(messageRef,{body:'Message unsent',deleted:true,deletedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(doc(alice,'conversations/'+friendshipId),{lastMessage:'Message unsent',updatedAt:serverTimestamp()}));
  const visible=await getDoc(doc(bob,'conversations/'+friendshipId+'/messages/message-1'));
  if(visible.data()?.body!=='Message unsent' || visible.data()?.deleted!==true)
    throw new Error('Unsent tombstone was not visible to both friends.');
});
