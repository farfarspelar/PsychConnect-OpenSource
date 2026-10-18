export function createTtlCache({ttlMs,maxEntries,clock=()=>Date.now()}) {
  const entries=new Map();
  function get(key) {
    const cached=entries.get(key);
    if(!cached) return undefined;
    if(cached.expiresAt<=clock()) { entries.delete(key); return undefined; }
    entries.delete(key); entries.set(key,cached);
    return cached.value;
  }
  function set(key,value) {
    entries.delete(key);
    while(entries.size>=maxEntries) entries.delete(entries.keys().next().value);
    entries.set(key,{value,expiresAt:clock()+ttlMs});
  }
  function clear(key) { if(key===undefined) entries.clear(); else entries.delete(key); }
  return {get,set,clear,get size(){return entries.size;}};
}
