import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { chat2payClient, queryKeys } from '@/shared/api/chat2payClient';
import type { ProfileLoginRequest, ProfileSummary } from '@/shared/api/contracts';
import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { BrandButton } from '@/shared/ui/BrandButton';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';
import { CloseIcon } from '@/shared/ui/icons';
import { useAuthStore } from '@/features/auth/useAuthStore';

function capabilityLabel(capability: ProfileSummary['supportedCapabilities'][number]) {
  if (capability === 'REGISTERED_PAYEE_LOOKUP') {
    return 'Payee lookup';
  }

  if (capability === 'DOMESTIC_PAYMENT') {
    return 'Domestic payment';
  }

  return 'International payment';
}

function ProfileCard({
  profile,
  busy,
  onEnter,
}: {
  profile: ProfileSummary;
  busy: boolean;
  onEnter: (profile: ProfileSummary) => void;
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
          <span className="text-brand-gray">Capabilities</span>
          <span className="text-right font-semibold text-brand-black">
            {profile.supportedCapabilities.map(capabilityLabel).join(' • ')}
          </span>
        </div>
      </div>
      <div className="mt-6">
        <BrandButton fullWidth onClick={() => onEnter(profile)} disabled={busy} loading={busy}>
          {busy ? 'Entering...' : 'Enter'}
        </BrandButton>
      </div>
    </div>
  );
}

function PasswordDialog({
  profile,
  password,
  errorMessage,
  busy,
  onPasswordChange,
  onClose,
  onSubmit,
}: {
  profile: ProfileSummary;
  password: string;
  errorMessage: string | null;
  busy: boolean;
  onPasswordChange: (value: string) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) {
        onClose();
      }
    }

    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('keydown', handleEscape);
    };
  }, [busy, onClose]);

  return (
    <div className="brand-dialog-backdrop" onClick={() => !busy && onClose()}>
      <div
        className="brand-dialog-panel"
        style={{ width: 'min(440px, 100%)' }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="profile-password-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="border-b border-brand-line px-6 pb-4 pt-5">
          <div className="pr-10">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">Access Check</p>
            <h3 id="profile-password-title" className="mt-2 text-lg font-semibold text-brand-black">
              Enter password to continue
            </h3>
            <p className="mt-2 text-sm leading-6 text-brand-gray">
              This internal POC uses one shared access password for all demo profiles.
            </p>
          </div>
          <button
            className="absolute right-5 top-5 flex h-9 w-9 items-center justify-center border border-brand-line bg-white text-brand-black transition hover:border-brand-red hover:text-brand-red disabled:cursor-not-allowed disabled:opacity-40"
            onClick={onClose}
            aria-label="Close password dialog"
            disabled={busy}
          >
            <CloseIcon className="h-4 w-4" />
          </button>
        </div>

        <form
          className="space-y-5 px-6 pb-6 pt-5"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <div className="border border-brand-line bg-brand-fog px-4 py-3">
            <div className="flex items-center gap-3">
              <BrandAvatar name={profile.displayName} size="sm" />
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-brand-black">{profile.displayName}</p>
                <p className="truncate text-xs uppercase tracking-[0.14em] text-brand-gray">{profile.code}</p>
              </div>
            </div>
          </div>

          <div>
            <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray" htmlFor="profile-password">
              Password
            </label>
            <input
              ref={inputRef}
              id="profile-password"
              className={['brand-input', errorMessage ? 'brand-input-error' : ''].filter(Boolean).join(' ')}
              type="password"
              value={password}
              onChange={(event) => onPasswordChange(event.target.value)}
              autoComplete="current-password"
              placeholder="Enter shared access password"
              disabled={busy}
            />
            {errorMessage ? (
              <p className="mt-2 text-sm font-medium text-red-700">{errorMessage}</p>
            ) : (
              <p className="mt-2 text-sm text-brand-gray">Use the shared internal password to unlock this workspace.</p>
            )}
          </div>

          <div className="flex items-center justify-end gap-3">
            <BrandButton type="button" variant="secondary" onClick={onClose} disabled={busy}>
              Cancel
            </BrandButton>
            <BrandButton type="submit" loading={busy} disabled={busy}>
              Continue
            </BrandButton>
          </div>
        </form>
      </div>
    </div>
  );
}

export function ProfileSelectorPage() {
  const navigate = useNavigate();
  const setCurrentUser = useAuthStore((state) => state.setCurrentUser);
  const [selectedProfile, setSelectedProfile] = useState<ProfileSummary | null>(null);
  const [password, setPassword] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const profilesQuery = useQuery({
    queryKey: queryKeys.profiles,
    queryFn: () => chat2payClient.listProfiles(),
  });

  const loginMutation = useMutation({
    mutationFn: (payload: ProfileLoginRequest) => chat2payClient.profileLogin(payload),
    onSuccess: (user) => {
      setSelectedProfile(null);
      setPassword('');
      setValidationError(null);
      setCurrentUser(user);
      navigate('/chat');
    },
  });

  const loginError =
    validationError ?? (loginMutation.error instanceof Error ? loginMutation.error.message : null);

  function openPasswordDialog(profile: ProfileSummary) {
    setSelectedProfile(profile);
    setPassword('');
    setValidationError(null);
    loginMutation.reset();
  }

  function closePasswordDialog() {
    if (loginMutation.isPending) {
      return;
    }

    setSelectedProfile(null);
    setPassword('');
    setValidationError(null);
    loginMutation.reset();
  }

  function updatePassword(value: string) {
    setPassword(value);

    if (validationError) {
      setValidationError(null);
    }

    if (loginMutation.isError) {
      loginMutation.reset();
    }
  }

  function submitPassword() {
    if (!selectedProfile) {
      return;
    }

    const trimmedPassword = password.trim();

    if (!trimmedPassword) {
      setValidationError('Password is required.');
      return;
    }

    setValidationError(null);
    loginMutation.mutate({
      profileId: selectedProfile.id,
      password: trimmedPassword,
    });
  }

  return (
    <>
      <main className="brand-shell app-grid min-h-full px-8 py-10">
        <div className="mx-auto flex min-h-[calc(100vh-80px)] max-w-7xl flex-col justify-center">
          <div className="mb-12 max-w-3xl">
            <p className="mb-4 text-xs font-semibold uppercase tracking-[0.24em] text-brand-red">Internal Transfer POC</p>
            <h1 className="text-5xl font-semibold tracking-tight text-brand-black">chat2pay</h1>
            <p className="mt-6 max-w-2xl text-base leading-8 text-brand-gray">
              Select a predefined profile, then enter the shared access password to continue into the payment workspace.
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
                  busy={loginMutation.isPending && loginMutation.variables?.profileId === profile.id}
                  onEnter={openPasswordDialog}
                />
              ))}
            </div>
          )}
        </div>
      </main>

      {selectedProfile ? (
        <PasswordDialog
          profile={selectedProfile}
          password={password}
          errorMessage={loginError}
          busy={loginMutation.isPending}
          onPasswordChange={updatePassword}
          onClose={closePasswordDialog}
          onSubmit={submitPassword}
        />
      ) : null}
    </>
  );
}
