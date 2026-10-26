-- Account deletion (shared/CONTRACTS.md, Account deletion). Google Play
-- requires apps with accounts to let users delete them from inside the app.
--
-- Deletes the caller's auth user. Every table's user_id references auth.users
-- ON DELETE CASCADE, so all of their data goes with it.

CREATE OR REPLACE FUNCTION public.delete_account()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
  END IF;
  DELETE FROM auth.users WHERE id = auth.uid();
END;
$$;

REVOKE ALL ON FUNCTION public.delete_account() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_account() TO authenticated;
