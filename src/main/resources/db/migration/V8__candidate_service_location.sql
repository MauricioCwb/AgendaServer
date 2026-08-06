ALTER TABLE agenda_candidates
    ADD COLUMN location_proposal VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL',
    ADD COLUMN proposed_latitude DOUBLE PRECISION NULL,
    ADD COLUMN proposed_longitude DOUBLE PRECISION NULL,
    ADD COLUMN agreed_location VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL';

ALTER TABLE agenda_candidates
    ADD CONSTRAINT ck_agenda_candidates_location_proposal
        CHECK (location_proposal IN ('ORIGINAL', 'PROVIDER')),
    ADD CONSTRAINT ck_agenda_candidates_agreed_location
        CHECK (agreed_location IN ('ORIGINAL', 'PROVIDER')),
    ADD CONSTRAINT ck_agenda_candidates_provider_coordinates
        CHECK (
            (location_proposal = 'ORIGINAL' AND proposed_latitude IS NULL AND proposed_longitude IS NULL)
            OR
            (location_proposal = 'PROVIDER' AND proposed_latitude IS NOT NULL AND proposed_longitude IS NOT NULL)
        );
