import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createService, getService, listServices, updateService } from '../../api/serviceApi';

function lastCall() {
  const [url, init] = vi.mocked(fetch).mock.calls[0];
  return { url: String(url), method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined };
}

describe('serviceApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({}), { status: 200 })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('creates with POST to the collection', async () => {
    await createService({ name: 'checkout-api', ownerId: 'u1' });

    const call = lastCall();
    expect(call.method).toBe('POST');
    expect(call.url).toMatch(/\/api\/v1\/services$/);
    expect(call.body).toEqual({ name: 'checkout-api', ownerId: 'u1' });
  });

  it('edits with PATCH to the item, not PUT and not the collection', async () => {
    await updateService('svc-1', { team: 'Storefront' });

    const call = lastCall();
    // PATCH matters -- backend leaves null fields unchanged; PUT semantics would wipe them.
    expect(call.method).toBe('PATCH');
    expect(call.url).toContain('/api/v1/services/svc-1');
    expect(call.body).toEqual({ team: 'Storefront' });
  });

  it('sends only the fields being changed on an edit', async () => {
    await updateService('svc-1', { availabilityTarget: 99.5 });

    expect(lastCall().body).toEqual({ availabilityTarget: 99.5 });
  });

  it('reads one service with GET and no body', async () => {
    await getService('svc-1');

    const call = lastCall();
    expect(call.method).toBe('GET');
    expect(call.body).toBeUndefined();
  });

  it('passes catalog filters through to the query string', async () => {
    await listServices({ q: 'checkout', status: 'ARCHIVED', team: 'Payments', size: 25 });

    const { url } = lastCall();
    expect(url).toContain('q=checkout');
    expect(url).toContain('status=ARCHIVED');
    expect(url).toContain('team=Payments');
  });
});
