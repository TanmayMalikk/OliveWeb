import {readFile} from 'node:fs/promises';

import {randomBytes} from 'node:crypto';

import {initializeApp,deleteApp} from 'firebase/app';

import {getAuth,createUserWithEmailAndPassword,signInWithEmailAndPassword,deleteUser,signOut,validatePassword} from 'firebase/auth';

import {getFirestore,doc,setDoc,getDoc,deleteDoc,terminate} from 'firebase/firestore';

const config=JSON.parse(await readFile(new URL('../app/google-services.json',import.meta.url),'utf8'));

if(config.project_info.project_id!=='olive-21239') throw Error('Unexpected project');

const options={apiKey:config.client[0].api_key[0].current_key,projectId:config.project_info.project_id};

const apps=[initializeApp(options,'olive-check-a'),initializeApp(options,'olive-check-b')];

const auth=apps.map(getAuth), db=apps.map(app=>getFirestore(app));

const username='check_'+randomBytes(6).toString('hex'), password='Ab'+randomBytes(3).toString('hex');

let user,reference;

try {
 const short=await validatePassword(auth[0],'Ab12345');
 const long=await validatePassword(auth[0],'A'.repeat(25));
 if(short.isValid || long.isValid) throw Error('Server password bounds not enforced');
 console.log('PASS: server rejects passwords below 8 or above 24 characters');
 user=(await createUserWithEmailAndPassword(auth[0],username+'@users.olive.invalid',password)).user;

 reference=doc(db[0],'users',user.uid);

 await setDoc(reference,{username,fullName:'Temporary setup check',affectedNames:'',dob:'2000-01-01',language:'English',situation:'',region:'',triggerHash:'a'.repeat(64),contacts:[],address:{line1:'123 Test Street',line2:'',city:'Edmonton',province:'Alberta',postalCode:'T5J 0N3',country:'Canada'}});

  console.log('PASS: registration with an eight-character password and profile write');

 try {await createUserWithEmailAndPassword(auth[1],username.toUpperCase().toLowerCase()+'@users.olive.invalid',password);throw Error('Duplicate username allowed');} catch(e){if(e.code!=='auth/email-already-in-use')throw e;}

 console.log('PASS: duplicate normalized username rejected');

 try {await signInWithEmailAndPassword(auth[1],username+'@users.olive.invalid',password.toLowerCase());throw Error('Incorrect password case allowed');} catch(e){if(!['auth/invalid-credential','auth/wrong-password'].includes(e.code))throw e;}

 console.log('PASS: incorrect password case rejected');

 try {await getDoc(doc(db[1],'users',user.uid));throw Error('Anonymous access unexpectedly allowed');} catch(e){if(e.code!=='permission-denied')throw e;}

 console.log('PASS: anonymous profile access denied');

 await signInWithEmailAndPassword(auth[1],username+'@users.olive.invalid',password);

 const restored=await getDoc(doc(db[1],'users',user.uid));

 if(restored.data()?.username!==username)throw Error('Profile restore failed');

 console.log('PASS: separate-client login and profile restoration');

} finally {

 if(reference)try {await deleteDoc(reference);} catch(e){console.error("Profile cleanup: "+e.code);}

 if(user)await deleteUser(user);

 await Promise.all(auth.map(a=>signOut(a)));

 await Promise.all(db.map(d=>terminate(d)));

 await Promise.all(apps.map(a=>deleteApp(a)));

 console.log('Temporary setup account and profile removed');

}





