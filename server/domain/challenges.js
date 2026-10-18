export function canExpireChallengeRoom(room) {
  return Boolean(room?.challengeId)&&room.phase==='waiting'&&room.players.length<2;
}
