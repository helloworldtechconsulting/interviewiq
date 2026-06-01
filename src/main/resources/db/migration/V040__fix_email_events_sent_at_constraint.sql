-- V040__fix_email_events_sent_at_constraint.sql

ALTER TABLE email_events
DROP CONSTRAINT ck_email_events_sent_at_after_created;

ALTER TABLE email_events
    ADD CONSTRAINT ck_email_events_sent_at_after_created
        CHECK (sent_at >= created_at);