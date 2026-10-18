CREATE TABLE IF NOT EXISTS solo_survival_runs (
  id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  score_ms INTEGER NOT NULL CHECK (score_ms >= 0 AND score_ms <= 86400000),
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS solo_survival_runs_score_idx
  ON solo_survival_runs(score_ms DESC, completed_at ASC);
CREATE INDEX IF NOT EXISTS solo_survival_runs_player_idx
  ON solo_survival_runs(player_id, completed_at DESC);
