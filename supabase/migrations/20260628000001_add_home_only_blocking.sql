-- Tasks marked home_only_blocking only contribute to blocking when the
-- user's current location is within the configured home radius. When the
-- user is away (e.g. on vacation), these tasks are treated as met and do
-- not block apps. Useful for routines tied to gear/equipment at home
-- (e.g. a hangboard workout).

ALTER TABLE tasks
  ADD COLUMN home_only_blocking BOOLEAN NOT NULL DEFAULT false;
