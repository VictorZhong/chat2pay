import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { chat2payClient, queryKeys } from '@/shared/api/chat2payClient';
import type { ProfileSummary } from '@/shared/api/contracts';
import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { BrandButton } from '@/shared/ui/BrandButton';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';
import { useAuthStore } from '@/features/auth/useAuthStore';

function ProfileCard({
  profile,
  busy,
  onEnter,
}: {
  profile: ProfileSummary;
  busy: boolean;
  onEnter: () => void;
}) {
  return (
    <div className="brand-panel relative p-6">
      <div className="absolute left-0 top-0 h-1 w-16 bg-brand-red" />
      <div className="mb-8 flex items-center gap-4">
        <BrandAvatar name={profile.displayName} size="lg" />
        <div className="min-w-0">
          <h3 className="truncate text-xl font-semibold text-brand-black">{profile.displayName}</h3>
          <p className="mt-1 truncate text-sm text-brand-gray">{profile.username}</p>
        </div>
      </div>
      <div className="space-y-3 border border-brand-line bg-brand-fog p-4">
        <div className="flex items-center justify-between gap-3 text-sm">
          <span className="text-brand-gray">Profile code</span>
          <span className="font-semibold text-brand-black">{profile.code}</span>
        </div>
        <div className="flex items-center justify-between gap-3 text-sm">
          <span className="text-brand-gray">Locale</span>
          <span className="font-semibold text-brand-black">{profile.locale}</span>
        </div>
        <div className="flex items-center justify-between gap-3 text-sm">
          <span className="text-brand-gray">Customer</span>
          <span className="font-semibold text-brand-black">{profile.mockCustomerId}</span>
        </div>
      </div>
      <div className="mt-6">
        <BrandButton fullWidth onClick={onEnter} disabled={busy}>
          {busy ? 'Entering...' : 'Enter'}
        </BrandButton>
      </div>
    </div>
  );
}

export function ProfileSelectorPage() {
  const navigate = useNavigate();
  const setCurrentUser = useAuthStore((state) => state.setCurrentUser);

  const profilesQuery = useQuery({
    queryKey: queryKeys.profiles,
    queryFn: () => chat2payClient.listProfiles(),
  });

  const loginMutation = useMutation({
    mutationFn: (profileId: string) => chat2payClient.profileLogin(profileId),
    onSuccess: (user) => {
      setCurrentUser(user);
      navigate('/chat');
    },
  });

  return (
    <main className="brand-shell app-grid min-h-full px-8 py-10">
      <div className="mx-auto flex min-h-[calc(100vh-80px)] max-w-7xl flex-col justify-center">
        <div className="mb-12 max-w-3xl">
          <p className="mb-4 text-xs font-semibold uppercase tracking-[0.24em] text-brand-red">Internal Transfer POC</p>
          <h1 className="text-5xl font-semibold tracking-tight text-brand-black">chat2pay</h1>
          <p className="mt-6 max-w-2xl text-base leading-8 text-brand-gray">
            Select a predefined profile to enter the conversational transfer workspace.
          </p>
        </div>

        {profilesQuery.isLoading ? (
          <div className="brand-panel flex min-h-[280px] items-center justify-center">
            <div className="w-full max-w-2xl px-6">
              <BrandLoadingPanel
                title="Loading profiles"
                description="Available demo identities are being prepared for this workspace."
              />
            </div>
          </div>
        ) : (
          <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
            {profilesQuery.data?.map((profile) => (
              <ProfileCard
                key={profile.id}
                profile={profile}
                busy={loginMutation.isPending && loginMutation.variables === profile.id}
                onEnter={() => loginMutation.mutate(profile.id)}
              />
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
