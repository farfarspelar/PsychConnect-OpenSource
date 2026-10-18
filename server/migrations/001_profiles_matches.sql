CREATE TABLE IF NOT EXISTS players (
  id UUID PRIMARY KEY,
  public_code VARCHAR(12) NOT NULL UNIQUE,
  nickname VARCHAR(24) NOT NULL,
  avatar_id SMALLINT NOT NULL DEFAULT 0 CHECK (avatar_id BETWEEN 0 AND 11),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  presence_hidden BOOLEAN NOT NULL DEFAULT false,
  challenges_enabled BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS player_credentials (
  player_id UUID PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
  token_salt TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS recovery_credentials (
  player_id UUID PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
  recovery_salt TEXT NOT NULL,
  recovery_hash TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS player_stats (
  player_id UUID PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
  total_matches INTEGER NOT NULL DEFAULT 0,
  versus_wins INTEGER NOT NULL DEFAULT 0,
  versus_losses INTEGER NOT NULL DEFAULT 0,
  versus_draws INTEGER NOT NULL DEFAULT 0,
  current_win_streak INTEGER NOT NULL DEFAULT 0,
  longest_win_streak INTEGER NOT NULL DEFAULT 0,
  rating INTEGER NOT NULL DEFAULT 1000,
  highest_rating INTEGER NOT NULL DEFAULT 1000,
  total_sync_seconds DOUBLE PRECISION NOT NULL DEFAULT 0,
  together_best_score INTEGER NOT NULL DEFAULT 0,
  together_sessions INTEGER NOT NULL DEFAULT 0,
  harmony_sessions INTEGER NOT NULL DEFAULT 0,
  harmony_seconds DOUBLE PRECISION NOT NULL DEFAULT 0,
  favorite_mode VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS matches (
  id UUID PRIMARY KEY,
  mode VARCHAR(16) NOT NULL CHECK (mode IN ('DEATHMATCH','CONNECTION','HARMONY')),
  status VARCHAR(16) NOT NULL CHECK (status IN ('active','completed','abandoned')),
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  duration_seconds INTEGER NOT NULL DEFAULT 0,
  halves INTEGER NOT NULL DEFAULT 1,
  winner_player_id UUID REFERENCES players(id) ON DELETE SET NULL,
  shared_score INTEGER,
  alignment_percent INTEGER,
  sync_seconds DOUBLE PRECISION NOT NULL DEFAULT 0,
  longest_sync_seconds DOUBLE PRECISION NOT NULL DEFAULT 0,
  abandoned_reason VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS match_participants (
  match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  role VARCHAR(16) NOT NULL,
  score INTEGER NOT NULL DEFAULT 0,
  result VARCHAR(8) NOT NULL CHECK (result IN ('win','loss','draw','coop')),
  rating_before INTEGER,
  rating_after INTEGER,
  PRIMARY KEY (match_id, player_id)
);

CREATE TABLE IF NOT EXISTS rating_events (
  match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  rating_delta INTEGER NOT NULL,
  rating_after INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (match_id, player_id)
);

CREATE TABLE IF NOT EXISTS rival_stats (
  player_low UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  player_high UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  matches_played INTEGER NOT NULL DEFAULT 0,
  low_wins INTEGER NOT NULL DEFAULT 0,
  high_wins INTEGER NOT NULL DEFAULT 0,
  draws INTEGER NOT NULL DEFAULT 0,
  streak_winner UUID REFERENCES players(id) ON DELETE SET NULL,
  current_streak INTEGER NOT NULL DEFAULT 0,
  longest_streak INTEGER NOT NULL DEFAULT 0,
  last_encounter_at TIMESTAMPTZ,
  last_winner UUID REFERENCES players(id) ON DELETE SET NULL,
  PRIMARY KEY (player_low, player_high),
  CHECK (player_low < player_high)
);

CREATE TABLE IF NOT EXISTS achievements (
  code VARCHAR(32) PRIMARY KEY,
  title VARCHAR(48) NOT NULL,
  description VARCHAR(160) NOT NULL
);

CREATE TABLE IF NOT EXISTS player_achievements (
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  code VARCHAR(32) NOT NULL REFERENCES achievements(code) ON DELETE CASCADE,
  rival_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (player_id, code, rival_id)
);

CREATE TABLE IF NOT EXISTS challenges (
  id UUID PRIMARY KEY,
  from_player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  to_player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  pair_key VARCHAR(80) NOT NULL,
  mode VARCHAR(16) NOT NULL DEFAULT 'DEATHMATCH' CHECK (mode IN ('DEATHMATCH','CONNECTION','HARMONY')),
  duration_seconds INTEGER NOT NULL DEFAULT 60 CHECK (duration_seconds IN (30,45,60)),
  halves INTEGER NOT NULL DEFAULT 2 CHECK (halves IN (2,4)),
  status VARCHAR(16) NOT NULL CHECK (status IN ('pending','accepted','declined','expired')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  responded_at TIMESTAMPTZ,
  session_code VARCHAR(8),
  CHECK (from_player_id <> to_player_id)
);

CREATE INDEX IF NOT EXISTS matches_mode_completed_at_idx ON matches(mode, completed_at DESC) WHERE status='completed';
CREATE INDEX IF NOT EXISTS match_participants_player_match_idx ON match_participants(player_id, match_id);
CREATE INDEX IF NOT EXISTS rating_events_player_created_idx ON rating_events(player_id, created_at DESC);
CREATE INDEX IF NOT EXISTS challenges_target_pending_idx ON challenges(to_player_id, expires_at) WHERE status='pending';
CREATE UNIQUE INDEX IF NOT EXISTS challenges_pending_pair_unique ON challenges(pair_key) WHERE status='pending';
CREATE INDEX IF NOT EXISTS player_achievements_player_idx ON player_achievements(player_id, unlocked_at DESC);

INSERT INTO achievements(code,title,description) VALUES
  ('first_encounter','First Encounter','Complete your first Versus match against this rival.'),
  ('sweet_revenge','Sweet Revenge','Defeat a rival who won your previous encounter.'),
  ('unfinished_business','Unfinished Business','Complete five Versus matches against this rival.'),
  ('nemesis','Nemesis','Complete ten Versus matches against this rival.'),
  ('perfect_balance','Perfect Balance','Reach exactly five wins each against this rival.'),
  ('comeback','Comeback','Win after this rival wins three consecutive encounters.')
ON CONFLICT(code) DO NOTHING;
