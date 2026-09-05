export type ApiErrorCode =
  | 'BACKEND_UNAVAILABLE'
  | 'TIMEOUT'
  | 'PEER_NOT_FOUND'
  | 'PEER_UNREACHABLE'
  | 'TRANSFER_NOT_FOUND'
  | 'TRANSFER_FAILED'
  | 'INVALID_REQUEST'
  | 'NOT_ALLOWED'
  | 'SERVER_ERROR'
  | 'UNKNOWN_ERROR';

export interface ApiNormalizedError {
  message: string;
  code: ApiErrorCode;
  status: number;
  details?: unknown;
}

/**
 * Custom error thrown when the backend returns a non-2xx HTTP status.
 */
export class MeshDropApiError extends Error {
  public statusCode: number;
  public details?: unknown;
  public code: ApiErrorCode;

  constructor(message: string, statusCode: number, details?: unknown) {
    super(message);
    this.name = 'MeshDropApiError';
    this.statusCode = statusCode;
    this.details = details;

    if (statusCode === 400) this.code = 'INVALID_REQUEST';
    else if (statusCode === 404) {
      if (message.toLowerCase().includes('peer')) this.code = 'PEER_NOT_FOUND';
      else if (message.toLowerCase().includes('transfer')) this.code = 'TRANSFER_NOT_FOUND';
      else this.code = 'INVALID_REQUEST';
    } else if (statusCode === 405) this.code = 'NOT_ALLOWED';
    else if (statusCode >= 500) this.code = 'SERVER_ERROR';
    else this.code = 'UNKNOWN_ERROR';
  }

  public toNormalized(): ApiNormalizedError {
    return {
      message: this.message,
      code: this.code,
      status: this.statusCode,
      details: this.details,
    };
  }
}

/**
 * Custom error thrown when the backend cannot be reached (offline, refused, timeout).
 */
export class NetworkError extends Error {
  public cause?: unknown;
  public code: ApiErrorCode;

  constructor(message: string = 'MeshDrop backend is unreachable', cause?: unknown) {
    super(message);
    this.name = 'NetworkError';
    this.cause = cause;
    this.code = message.toLowerCase().includes('time') ? 'TIMEOUT' : 'BACKEND_UNAVAILABLE';
  }

  public toNormalized(): ApiNormalizedError {
    return {
      message: this.message,
      code: this.code,
      status: 0,
      details: this.cause,
    };
  }
}

/**
 * Normalizes any caught error into a predictable ApiNormalizedError object.
 */
export function normalizeError(err: unknown): ApiNormalizedError {
  if (err instanceof MeshDropApiError) {
    return err.toNormalized();
  }
  if (err instanceof NetworkError) {
    return err.toNormalized();
  }
  const message = err instanceof Error ? err.message : String(err);
  return {
    message,
    code: 'UNKNOWN_ERROR',
    status: 500,
  };
}

export interface ApiClientConfig {
  baseUrl?: string;
  timeoutMillis?: number;
}

export class ApiClient {
  private baseUrl: string;
  private timeoutMillis: number;

  constructor(config: ApiClientConfig = {}) {
    // Read from Vite environment variable or default to local Java backend port 8080
    this.baseUrl = config.baseUrl || (import.meta.env?.VITE_MESHDROP_API_URL as string) || 'http://localhost:8080';
    this.timeoutMillis = config.timeoutMillis || 4000;
  }

  public getBaseUrl(): string {
    return this.baseUrl;
  }

  public setBaseUrl(url: string) {
    this.baseUrl = url.replace(/\/+$/, '');
  }

  /**
   * Dispatches an HTTP request with automatic JSON handling and timeout guards.
   */
  public async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMillis);

    const headers = new Headers(options.headers || {});
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json');
    }
    if (options.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }

    try {
      const response = await fetch(url, {
        ...options,
        headers,
        signal: controller.signal,
      });

      if (!response.ok) {
        let errorMessage = `HTTP ${response.status} ${response.statusText}`;
        let details: unknown = null;
        try {
          const body = await response.json();
          details = body;
          if (body && typeof body === 'object' && 'error' in body) {
            errorMessage = String(body.error);
          }
        } catch {
          // ignore non-JSON response body
        }
        throw new MeshDropApiError(errorMessage, response.status, details);
      }

      if (response.status === 204) {
        return null as unknown as T;
      }

      return (await response.json()) as T;
    } catch (err: unknown) {
      if (err instanceof MeshDropApiError) {
        throw err;
      }

      if (err instanceof DOMException && err.name === 'AbortError') {
        throw new NetworkError('Request timed out connecting to MeshDrop backend');
      }

      // Check for connection refused / failed to fetch
      throw new NetworkError('MeshDrop backend is unreachable', err);
    } finally {
      clearTimeout(timer);
    }
  }

  public get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'GET' });
  }

  public post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, {
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
    });
  }

  public delete<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: 'DELETE' });
  }
}

export const apiClient = new ApiClient();
