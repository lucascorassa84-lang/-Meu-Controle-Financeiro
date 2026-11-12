-- Executar uma vez no SQL Editor do projeto Supabase correto, com acesso de administrador.
-- Vincula os dois usuários previamente cadastrados à mesma família.
-- Não desativa RLS nem altera políticas existentes.
BEGIN;
DO $$
DECLARE
  owner_id uuid := '6848f420-3ee3-460d-81c9-502d998baee5';
  spouse_id uuid := '0073e752-6e6f-4cfb-ab09-c26ae1aa3d19';
  target_family uuid;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = owner_id)
     OR NOT EXISTS (SELECT 1 FROM auth.users WHERE id = spouse_id) THEN
    RAISE EXCEPTION 'Usuários não encontrados no Supabase Auth; nenhuma alteração realizada';
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

-- Conferência administrativa: ambos devem aparecer na mesma family_id.
SELECT f.id AS family_id, f.created_by, m.user_id
FROM public.families f
JOIN public.family_members m ON m.family_id = f.id
WHERE f.created_by = '6848f420-3ee3-460d-81c9-502d998baee5'::uuid
ORDER BY f.created_at, f.id, m.user_id;
