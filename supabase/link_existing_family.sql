-- Execute no SQL Editor do projeto correto após conferir que há exatamente dois usuários.
-- Usa IDs reais do auth.users; não utiliza identificadores parciais nem presumidos.
-- Requer acesso administrativo. Não desativa RLS.
BEGIN;
DO $$
DECLARE
  owner_id uuid;
  spouse_id uuid;
  target_family uuid;
  user_count integer;
BEGIN
  SELECT count(*) INTO user_count FROM auth.users;
  IF user_count <> 2 THEN
    RAISE EXCEPTION 'Esperados exatamente 2 usuários; encontrados %. Vinculação cancelada.', user_count;
  END IF;
  SELECT id INTO owner_id FROM auth.users ORDER BY created_at, id LIMIT 1;
  SELECT id INTO spouse_id FROM auth.users ORDER BY created_at, id OFFSET 1 LIMIT 1;
  IF owner_id = spouse_id OR owner_id IS NULL OR spouse_id IS NULL THEN
    RAISE EXCEPTION 'Identificação dos usuários falhou';
  END IF;
  SELECT id INTO target_family FROM public.families
   WHERE created_by = owner_id ORDER BY created_at, id LIMIT 1 FOR UPDATE;
  IF target_family IS NULL THEN
    INSERT INTO public.families (created_by)
    VALUES (owner_id) RETURNING id INTO target_family;
  END IF;
  INSERT INTO public.family_members (family_id, user_id)
  VALUES (target_family, owner_id), (target_family, spouse_id)
  ON CONFLICT (family_id, user_id) DO NOTHING;
  RAISE NOTICE 'Família vinculada: %', target_family;
END $$;
COMMIT;
SELECT f.id AS family_id, m.user_id
FROM public.families f JOIN public.family_members m ON m.family_id=f.id
WHERE f.created_by=(SELECT id FROM auth.users ORDER BY created_at,id LIMIT 1)
ORDER BY f.created_at,f.id,m.user_id;
