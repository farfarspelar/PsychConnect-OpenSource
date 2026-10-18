/** Persist a profile match before starting gameplay, tolerating disconnects during the DB write. */
export async function prepareRound(room,{repository,presence,startRound,createMatchId,reportError=()=>{}}) {
  if(room.phase!=='active'||room.players.length!==2) return false;
  // The early experimental modes do not have profile-ranking schemas yet.
  if(repository&&['DEATHMATCH','CONNECTION','HARMONY'].includes(room.mode)&&room.players.every(player=>player.profileId)) {
    const matchId=createMatchId();
    room.matchId=matchId;
    room.matchSamples=[];
    room.matchStartedAt=new Date();
    const pending=repository.beginMatch({
      id:matchId,mode:room.mode,startedAt:room.matchStartedAt,
      durationSeconds:room.duration*room.matchHalves,halves:room.matchHalves,
      participants:room.players.map(player=>({profileId:player.profileId,role:player.role}))
    });
    room.matchPersistencePromise=pending;
    try { await pending; }
    catch(error) {
      if(room.matchId===matchId&&room.phase==='active') {
        room.matchId=null; room.matchSamples=[]; room.phase='ready'; room.ready.clear();
        reportError(room,error);
      }
      return false;
    }
    if(room.matchId!==matchId||room.phase!=='active'||room.players.length!==2) {
      await repository.abandonMatch(matchId,'disconnect').catch(error=>reportError(room,error));
      return false;
    }
  }
  if(room.phase!=='active'||room.players.length!==2) return false;
  room.players.forEach(player=>presence.setInMatch(player.ws,true));
  startRound(room);
  return true;
}
