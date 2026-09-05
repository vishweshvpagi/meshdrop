import { describe, expect, it, vi } from 'vitest';
import { renderToString } from 'react-dom/server';
import { Badge } from '../components/Badge/Badge';
import { Card } from '../components/Card/Card';
import { EmptyState } from '../components/EmptyState/EmptyState';
import { ErrorState } from '../components/ErrorState/ErrorState';
import { LoadingState } from '../components/LoadingState/LoadingState';
import { StatusIndicator } from '../components/StatusIndicator/StatusIndicator';

describe('Component States and Rendering', () => {
  it('renders LoadingState with custom message', () => {
    const html = renderToString(<LoadingState message="Connecting to MeshDrop..." />);
    expect(html).toContain('Connecting to MeshDrop...');
    expect(html).toContain('loading-state');
    expect(html).toContain('loading-spinner');
  });

  it('renders ErrorState with title and message', () => {
    const html = renderToString(
      <ErrorState
        title="Connection Failed"
        message="Could not reach node at 127.0.0.1:8080"
        onRetry={vi.fn()}
      />
    );
    expect(html).toContain('Connection Failed');
    expect(html).toContain('Could not reach node at 127.0.0.1:8080');
    expect(html).toContain('Try again');
  });

  it('renders EmptyState for peers', () => {
    const html = renderToString(
      <EmptyState
        title="No peers discovered"
        description="Waiting for UDP discovery announcements on your local network."
      />
    );
    expect(html).toContain('No peers discovered');
    expect(html).toContain('Waiting for UDP discovery announcements');
  });

  it('renders Peer card information correctly', () => {
    const html = renderToString(
      <Card title="PC-2">
        <div>
          <StatusIndicator status="connected" label="CONNECTED" />
          <span>192.168.1.25:5000</span>
          <Badge variant="success">READY</Badge>
        </div>
      </Card>
    );
    expect(html).toContain('PC-2');
    expect(html).toContain('CONNECTED');
    expect(html).toContain('192.168.1.25:5000');
    expect(html).toContain('READY');
  });

  it('renders Connection information correctly', () => {
    const html = renderToString(
      <div className="quick-conn-item">
        <span className="conn-title">PC-2</span>
        <Badge variant="neutral">OUTBOUND</Badge>
        <span className="conn-remote-addr">/192.168.1.25:5000</span>
        <Badge variant="success">READY</Badge>
      </div>
    );
    expect(html).toContain('PC-2');
    expect(html).toContain('OUTBOUND');
    expect(html).toContain('/192.168.1.25:5000');
    expect(html).toContain('READY');
  });
});
