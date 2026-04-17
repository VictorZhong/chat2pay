import { afterEach, describe, expect, it, vi } from 'vitest';
import { backendClient } from '@/shared/api/backendClient';

describe('backendClient', () => {
  const fetchMock = vi.fn<typeof fetch>();

  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it('sends the current profile id when calling authenticated chat endpoints', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          items: [],
          page: 0,
          pageSize: 20,
          total: 0,
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await backendClient.listChatSessions('profile_payment10');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/chat/sessions',
      expect.objectContaining({
        method: 'GET',
        headers: expect.any(Headers),
      }),
    );

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers;
    expect(headers.get('X-Profile-Id')).toBe('profile_payment10');
  });

  it('surfaces backend error messages from the JSON error payload', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'PROFILE_AUTH_FAILED',
          message: 'Incorrect password.',
          timestamp: '2026-04-17T00:00:00Z',
        }),
        {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      backendClient.profileLogin({
        profileId: 'profile_payment10',
        password: 'wrong-password',
      }),
    ).rejects.toThrow('Incorrect password.');
  });
});
