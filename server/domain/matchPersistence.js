/** Store an authoritative result and report persistence outcome without changing game scoring. */
export async function persistCompletedMatch({repository,match,onUnlocks=()=>{},onFailure=()=>{},onError=()=>{}}) {
  let result;
  try {
    result=await repository.completeMatch(match);
  } catch(error) {
    onError(error);
    onFailure('write_failed');
    return false;
  }
  if(!result?.recorded) { onFailure('not_recorded'); return false; }
  try { onUnlocks(result.unlocks||[]); } catch(error) { onError(error); }
  return true;
}
