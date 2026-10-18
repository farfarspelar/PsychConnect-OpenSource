import { SERVER_CONFIG } from '../config.js';

export const INITIAL_RATING=SERVER_CONFIG.rating.initial;
export const ELO_K=SERVER_CONFIG.rating.eloK;
export function expectedScore(rating,opponentRating) { return 1/(1+10**((opponentRating-rating)/400)); }
export function eloPair(ratingA,ratingB,outcomeA,k=ELO_K) {
  const deltaA=Math.round(k*(outcomeA-expectedScore(ratingA,ratingB)));
  const deltaB=Math.round(k*((1-outcomeA)-expectedScore(ratingB,ratingA)));
  return {ratingA:ratingA+deltaA,ratingB:ratingB+deltaB,deltaA,deltaB};
}
export function utcWeekStart(date=new Date()) {
  const start=new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth(),date.getUTCDate()));
  const daysSinceMonday=(start.getUTCDay()+6)%7;
  start.setUTCDate(start.getUTCDate()-daysSinceMonday);
  return start;
}
