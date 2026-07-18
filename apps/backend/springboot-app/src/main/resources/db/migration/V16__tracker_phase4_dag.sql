-- Phase 4 WP4.1: activate a versioned capability dependency DAG.
-- V6 rows are preserved as the superseded all-OR interpretation. The active
-- graph copies the same 29 dependencies into singleton OR groups so the groups
-- combine as mandatory AND inputs.

CREATE TABLE capability_graph_version (
  version_label    VARCHAR2(40) PRIMARY KEY,
  node_set_version VARCHAR2(40) NOT NULL,
  active           CHAR(1) DEFAULT 'N' NOT NULL,
  edge_count       NUMBER(3) NOT NULL,
  edge_sha256      CHAR(64) NOT NULL,
  notes            VARCHAR2(1000) NOT NULL,
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  CONSTRAINT ck_graph_version_active CHECK (active IN ('Y','N')),
  CONSTRAINT ck_graph_version_count CHECK (edge_count BETWEEN 0 AND 999),
  CONSTRAINT ck_graph_version_sha CHECK (LENGTH(edge_sha256) = 64)
);

INSERT INTO capability_graph_version (
  version_label, node_set_version, active, edge_count, edge_sha256, notes
) VALUES (
  'graph-v0-legacy', 'nodes-v1.0', 'N', 29,
  '5d22687ffb5142b1f72e948383e6e431346b78e97546b2e52780e0fec6704fb2',
  'V6 rows preserved; shared group 1 encodes the superseded all-OR reading'
);

INSERT INTO capability_graph_version (
  version_label, node_set_version, active, edge_count, edge_sha256, notes
) VALUES (
  'graph-v1.0', 'nodes-v1.0', 'Y', 29,
  'f5f948b35aa60ce4c72e3550ad188cc4a1e63595096bf64a9da022e7e5313e4e',
  'Mandatory pillar-local integration dependencies; singleton OR groups combine by AND'
);

ALTER TABLE capability_edge ADD graph_version_label VARCHAR2(40)
  DEFAULT 'graph-v0-legacy' NOT NULL;
ALTER TABLE capability_edge ADD CONSTRAINT fk_edge_graph_version
  FOREIGN KEY (graph_version_label)
  REFERENCES capability_graph_version(version_label);
ALTER TABLE capability_edge DROP CONSTRAINT uq_edge;
ALTER TABLE capability_edge ADD CONSTRAINT uq_edge_version
  UNIQUE (graph_version_label, to_node_id, from_node_id);
ALTER TABLE capability_edge ADD CONSTRAINT ck_edge_delta_range
  CHECK (delta_e BETWEEN 0 AND 0.5);
ALTER TABLE capability_edge ADD CONSTRAINT ck_edge_or_group_range
  CHECK (or_group BETWEEN 1 AND 99);

INSERT INTO capability_edge (
  to_node_id, from_node_id, or_group, delta_e, graph_version_label
)
SELECT legacy.to_node_id,
       legacy.from_node_id,
       ROW_NUMBER() OVER (
         PARTITION BY legacy.to_node_id
         ORDER BY source.code
       ),
       legacy.delta_e,
       'graph-v1.0'
  FROM capability_edge legacy
  JOIN capability_node source ON source.id = legacy.from_node_id
 WHERE legacy.graph_version_label = 'graph-v0-legacy';

ALTER TABLE pillar_snapshot ADD raw_readiness NUMBER(6,5);
ALTER TABLE pillar_snapshot ADD graph_version VARCHAR2(40);
ALTER TABLE pillar_snapshot ADD CONSTRAINT fk_snapshot_graph_version
  FOREIGN KEY (graph_version)
  REFERENCES capability_graph_version(version_label);

COMMENT ON TABLE capability_graph_version IS
  'Immutable capability dependency graph registry; application requires one active version';
COMMENT ON COLUMN pillar_snapshot.raw_readiness IS
  'Weighted observed readiness before dependency caps';
COMMENT ON COLUMN pillar_snapshot.graph_version IS
  'Capability dependency graph used for the effective readiness calculation';
