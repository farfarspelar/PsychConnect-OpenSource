export function bindAuthenticatedProfile(ws,profile,presence) {
  presence.register(profile,ws);
  ws.profileId=profile.id;
}

export function createPresenceRegistry() {
  const profiles=new Map();
  const sockets=new WeakMap();
  function register(profile,ws,inMatch=false) {
    const boundProfileId=sockets.get(ws);
    if(boundProfileId&&boundProfileId!==profile.id) {
      throw Object.assign(new Error('A WebSocket connection cannot change profile identity.'),{code:'PROFILE_SOCKET_IDENTITY_LOCKED'});
    }
    let entry=profiles.get(profile.id);
    if(!entry) { entry={profile,connections:new Map()}; profiles.set(profile.id,entry); }
    const previous=entry.connections.get(ws);
    entry.profile=profile; entry.connections.set(ws,{inMatch:previous?.inMatch??inMatch}); sockets.set(ws,profile.id);
    return stateFor(profile.id);
  }
  function setInMatch(ws,value) {
    const id=sockets.get(ws),entry=id&&profiles.get(id),connection=entry?.connections.get(ws);
    if(connection) connection.inMatch=Boolean(value);
  }
  function remove(ws) {
    const id=sockets.get(ws); if(!id) return;
    const entry=profiles.get(id); entry?.connections.delete(ws); sockets.delete(ws);
    if(entry?.connections.size===0) profiles.delete(id);
  }
  function removeProfile(id) {
    const entry=profiles.get(id); if(!entry) return;
    for(const ws of entry.connections.keys()) { sockets.delete(ws); try { ws.close(4001,'profile deleted'); } catch { /* already closed */ } }
    profiles.delete(id);
  }
  function updateProfile(profile) { const entry=profiles.get(profile.id); if(entry) entry.profile=profile; }
  function liveConnections(entry) {
    return entry?[...entry.connections.keys()].filter(ws=>ws.readyState===1):[];
  }
  function stateFor(id) {
    const entry=profiles.get(id); if(!entry) return 'Offline';
    const live=liveConnections(entry);
    if(live.length===0) return 'Offline';
    if(entry.profile.presenceHidden||!entry.profile.challengesEnabled) return 'Unavailable';
    return live.some(ws=>entry.connections.get(ws)?.inMatch)?'In Match':'Online';
  }
  function challengeStateFor(id) {
    const entry=profiles.get(id); if(!entry) return 'Offline';
    const live=liveConnections(entry);
    if(live.length===0) return 'Offline';
    if(live.some(ws=>entry.connections.get(ws)?.inMatch)) return 'In Match';
    return entry.profile.challengesEnabled?'Online':'Unavailable';
  }
  function sendToProfile(id,message) {
    const entry=profiles.get(id); if(!entry) return false;
    let sent=false;
    const payload=JSON.stringify(message);
    for(const ws of liveConnections(entry)) {
      try { ws.send(payload); sent=true; }
      catch { entry.connections.delete(ws); sockets.delete(ws); }
    }
    if(entry.connections.size===0) profiles.delete(id);
    return sent;
  }
  return {
    register,setInMatch,remove,removeProfile,updateProfile,stateFor,challengeStateFor,sendToProfile,
    isOnline:id=>liveConnections(profiles.get(id)).length>0,
    isInMatch:id=>{ const entry=profiles.get(id); return liveConnections(entry).some(ws=>entry.connections.get(ws)?.inMatch); }
  };
}
