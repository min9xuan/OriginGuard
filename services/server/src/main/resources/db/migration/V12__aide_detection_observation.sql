ALTER TABLE agent_observation
    DROP CONSTRAINT agent_observation_evidence_type_check;

ALTER TABLE agent_observation
    ADD CONSTRAINT agent_observation_evidence_type_check
        CHECK (evidence_type IN (
            'MEDIA_METADATA',
            'BASIC_MEDIA_FORENSICS',
            'FILE_INTEGRITY',
            'IMAGE_METADATA',
            'PERCEPTUAL_SIMILARITY',
            'AIGC_DETECTION',
            'LEGACY_RAG_GUIDANCE'
        ));
