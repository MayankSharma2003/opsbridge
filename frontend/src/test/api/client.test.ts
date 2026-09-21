import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, ApiError, NetworkError } from '../../api/client';
import { setAuthToken } from '../../api/tokenHolder';

describe('apiRequest', () => {
  beforeEach(() => {
    setAuthToken(null);
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('does not attach an Authorization header when no token is set', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));

    await apiRequest('/api/v1/me');

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = init?.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });

  it('attaches the Authorization header when a token is set', async () => {
    setAuthToken('jwt-token');
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));

    await apiRequest('/api/v1/me');

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = init?.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer jwt-token');
  });

  it('returns the parsed JSON body on a 2xx response', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ name: 'Checkout API' }), { status: 200 }));

    const result = await apiRequest<{ name: string }>('/api/v1/services/1');

    expect(result).toEqual({ name: 'Checkout API' });
  });

  it('throws an ApiError carrying the backend status/code/message on a non-2xx response', async () => {
    const body = {
      timestamp: '2026-03-01T00:00:00Z',
      status: 401,
      code: 'INVALID_CREDENTIALS',
      message: 'Email or password is incorrect.',
      path: '/api/v1/auth/login',
      traceId: null,
    };
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify(body), { status: 401 }));

    await expect(apiRequest('/api/v1/auth/login')).rejects.toMatchObject({
      status: 401,
      code: 'INVALID_CREDENTIALS',
      message: 'Email or password is incorrect.',
    });
  });

  it('throws ApiError specifically, not a plain Error, on a non-2xx response', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(
        JSON.stringify({ status: 404, code: 'RESOURCE_NOT_FOUND', message: 'Not found.', timestamp: '', path: '', traceId: null }),
        { status: 404 },
      ),
    );

    await expect(apiRequest('/api/v1/services/missing')).rejects.toBeInstanceOf(ApiError);
  });

  it('throws a NetworkError when fetch itself fails', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(apiRequest('/api/v1/me')).rejects.toBeInstanceOf(NetworkError);
  });

  it('sends a JSON body and Content-Type header for a POST with a body', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));

    await apiRequest('/api/v1/auth/login', { method: 'POST', body: { email: 'a@b.com', password: 'x' } });

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = init?.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(init?.body).toBe(JSON.stringify({ email: 'a@b.com', password: 'x' }));
  });
});
