import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { ApiClient, MeshDropApiError, NetworkError } from '../services/apiClient';

describe('ApiClient', () => {
  let client: ApiClient;
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    client = new ApiClient({ baseUrl: 'http://localhost:8080', timeoutMillis: 1000 });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('handles successful API requests', async () => {
    const mockData = { nodeId: 'test-123', displayName: 'NodeA', running: true };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockData),
    });

    const res = await client.get('/api/status');
    expect(res).toEqual(mockData);
  });

  it('handles HTTP errors (4xx / 5xx) with MeshDropApiError', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      json: () => Promise.resolve({ error: 'Storage failure' }),
    });

    await expect(client.get('/api/status')).rejects.toThrow(MeshDropApiError);
    await expect(client.get('/api/status')).rejects.toMatchObject({
      statusCode: 500,
      message: 'Storage failure',
    });
  });

  it('handles backend offline / network failure with NetworkError', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(client.get('/api/status')).rejects.toThrow(NetworkError);
    await expect(client.get('/api/status')).rejects.toThrow('MeshDrop backend is unreachable');
  });

  it('handles timeout with user-friendly NetworkError', async () => {
    const abortErr = new DOMException('The operation was aborted', 'AbortError');
    globalThis.fetch = vi.fn().mockRejectedValue(abortErr);

    await expect(client.get('/api/status')).rejects.toThrow(
      'Request timed out connecting to MeshDrop backend'
    );
  });
});
