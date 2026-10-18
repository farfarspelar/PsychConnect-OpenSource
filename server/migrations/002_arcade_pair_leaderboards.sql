CREATE TABLE IF NOT EXISTS arcade_pair_runs (
  id UUID PRIMARY KEY,
  mode VARCHAR(16) NOT NULL CHECK (mode IN ('COOP_SURVIVAL','PUZZLE')),
  player_a UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  player_b UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  score INTEGER NOT NULL CHECK (score >= 0),
  level INTEGER NOT NULL DEFAULT 0 CHECK (level >= 0),
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (player_a < player_b)
);

CREATE INDEX IF NOT EXISTS arcade_pair_runs_mode_score_idx
  ON arcade_pair_runs(mode, score DESC, completed_at DESC);
CREATE INDEX IF NOT EXISTS arcade_pair_runs_players_idx
  ON arcade_pair_runs(player_a, player_b, mode, completed_at DESC);
