ALTER TABLE build_graph_layouts
  ADD COLUMN IF NOT EXISTS anchors_json JSONB NOT NULL DEFAULT '{}'::jsonb;
