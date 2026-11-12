-- Run in Supabase SQL Editor after creating your project.
-- Auth users must be registered using Supabase Auth; never expose service_role keys.
create table if not exists public.families (
 id uuid primary key default gen_random_uuid(),
 created_by uuid not null references auth.users(id),
 created_at timestamptz not null default now()
);
create table if not exists public.family_members (
 family_id uuid not null references public.families(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 primary key (family_id,user_id)
);
create table if not exists public.family_movements (
 id uuid primary key,
 family_id uuid not null references public.families(id) on delete cascade,
 description text not null,
 amount_cents bigint not null check (amount_cents > 0),
 kind text not null check (kind in ('INCOME','EXPENSE')),
 created_by uuid not null default auth.uid() references auth.users(id),
 created_at timestamptz not null default now()
);
create index if not exists family_movements_family_idx on public.family_movements(family_id,created_at desc);
create or replace function public.is_family_member(fid uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists(select 1 from public.family_members m where m.family_id=fid and m.user_id=(select auth.uid())) $$;
revoke all on function public.is_family_member(uuid) from public;
grant execute on function public.is_family_member(uuid) to authenticated;
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.family_movements enable row level security;
create policy "family owners can read" on public.families for select to authenticated
 using (public.is_family_member(id));
create policy "owners can create families" on public.families for insert to authenticated
 with check (created_by=(select auth.uid()));
create policy "members read membership" on public.family_members for select to authenticated
 using (public.is_family_member(family_id));
create policy "owners can add members" on public.family_members for insert to authenticated
 with check (exists(select 1 from public.families f where f.id=family_id and f.created_by=(select auth.uid())));
create policy "members read movements" on public.family_movements for select to authenticated
 using (public.is_family_member(family_id));
create policy "members add movements" on public.family_movements for insert to authenticated
 with check (public.is_family_member(family_id) and created_by=(select auth.uid()));
create policy "members edit movements" on public.family_movements for update to authenticated
 using (public.is_family_member(family_id)) with check (public.is_family_member(family_id));
create policy "members delete movements" on public.family_movements for delete to authenticated
 using (public.is_family_member(family_id));
-- Onboarding must atomically insert family and first membership in a trusted
-- server-side function. Do not insert family members from an untrusted client
-- before verifying a signed invitation.
