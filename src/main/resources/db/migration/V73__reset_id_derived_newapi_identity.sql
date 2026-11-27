-- The New API username used to be derived from the account id (hub-u<id>), which made the two
-- sides impossible to line up: an account created on New API could never be recognised here, and a
-- local account could not be found there by the name its owner knows.
--
-- The name is now the shared identity, so every binding recorded under the old scheme points at a
-- New API user whose name no longer matches. Those rows are test data and are dropped rather than
-- migrated: the next sign in re-creates the binding under the real name, and the relay token is
-- re-provisioned on the next AI call.

DELETE FROM user_gateway_tokens;
DELETE FROM newapi_accounts;
