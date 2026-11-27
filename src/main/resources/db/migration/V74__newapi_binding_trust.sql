-- A name is not proof of ownership. Until an account has been created through a flow that proves
-- the two sides belong to the same person (local registration, or a sign in that already owned the
-- local password), the local account and the New API user with the same name are merely namesakes.
--
-- Without this flag, anyone able to register 'alice' on New API could sign in here and be handed
-- the existing local 'alice' account — its history, files and balance included.

ALTER TABLE newapi_accounts
    ADD COLUMN trusted BOOLEAN NOT NULL DEFAULT FALSE AFTER username;

-- Existing rows were bound by name alone, so they start untrusted on purpose: the flow that created
-- them predates the ownership check.
