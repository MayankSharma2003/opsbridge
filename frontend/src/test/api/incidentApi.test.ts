import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assignIncident, createIncident, listIncidents, transitionIncident } from '../../api/incidentApi';

// Store tests mock this module wholesale, so only these tests would catch a wrong verb, path, or query key.
function lastCall() {
  const [url, init] = vi.mocked(fetch).mock.calls[0];
  return { url: String(url), method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined };
}

describe('incidentApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({}), { status: 200 })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('creates an incident with POST, not PATCH', async () => {
    await createIncident({ title: 'Checkout down', serviceId: 'svc-1', priority: 'P1', severity: 'SEV1' });

    const call = lastCall();
    expect(call.method).toBe('POST');
    expect(call.url).toContain('/api/v1/incidents');
    expect(call.url).not.toMatch(/\/api\/v1\/incidents\/.+/);
  });

  it('sends the create payload as the body, and no reporter', async () => {
    await createIncident({
      title: 'Checkout down',
      description: 'Cards failing',
      serviceId: 'svc-1',
      priority: 'P1',
      severity: 'SEV1',
    });

    // Reporter comes from the token server-side -- sending one would be a spoofing vector.
    expect(lastCall().body).toEqual({
      title: 'Checkout down',
      description: 'Cards failing',
      serviceId: 'svc-1',
      priority: 'P1',
      severity: 'SEV1',
    });
    expect(lastCall().body).not.toHaveProperty('reporterId');
  });

  it('transitions status with PATCH against the status sub-resource', async () => {
    await transitionIncident('inc-1', { expectedVersion: 3, targetStatus: 'RESOLVED' });

    const call = lastCall();
    expect(call.method).toBe('PATCH');
    expect(call.url).toContain('/api/v1/incidents/inc-1/status');
    expect(call.body.expectedVersion).toBe(3);
  });

  it('assigns against the assign sub-resource, not the status one', async () => {
    await assignIncident('inc-1', { expectedVersion: 3, assigneeId: 'u2' });

    expect(lastCall().url).toContain('/api/v1/incidents/inc-1/assign');
  });

  it('builds the list query from the filters it is given', async () => {
    await listIncidents({ q: 'checkout', status: 'OPEN', priority: 'P1', page: 2, size: 25 });

    const { url } = lastCall();
    expect(url).toContain('q=checkout');
    expect(url).toContain('status=OPEN');
    expect(url).toContain('priority=P1');
    expect(url).toContain('page=2');
  });

  it('omits absent filters instead of sending empty params', async () => {
    await listIncidents({ size: 7 });

    const { url } = lastCall();
    expect(url).toContain('size=7');
    expect(url).not.toContain('status=');
    expect(url).not.toContain('q=');
  });
});
