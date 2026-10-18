const rateKey=request=>String(request.headers['x-forwarded-for']||request.socket?.remoteAddress||'unknown').split(',')[0].trim();

export function createRequestRateLimiter(configuration,clock=()=>Date.now()) {
  const buckets=new Map();
  function limited(request,bucketName='general') {
    const rule=configuration[bucketName];
    if(!rule) throw new Error(`Unknown request rate-limit bucket: ${bucketName}`);
    const now=clock(),key=`${rateKey(request)}:${bucketName}`;
    const previous=buckets.get(key);
    if(previous) buckets.delete(key);
    let state=previous;
    if(!state||now-state.start>=rule.windowMs) {
      if(buckets.size>=configuration.maxBuckets) {
        for(const [oldKey,oldState] of buckets) if(now-oldState.start>configuration.staleBucketMs) buckets.delete(oldKey);
        while(buckets.size>=configuration.maxBuckets) buckets.delete(buckets.keys().next().value);
      }
      state={start:now,count:0};
    }
    state.count++;
    buckets.set(key,state);
    return state.count>rule.maximum;
  }
  return {limited,get size(){return buckets.size;}};
}
