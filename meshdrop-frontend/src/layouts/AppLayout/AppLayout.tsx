import React, { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { StatusIndicator, StatusType } from '../../components/StatusIndicator/StatusIndicator';
import { ThemeToggle } from '../../components/ThemeToggle/ThemeToggle';
import { useNodeStatus } from '../../hooks/useNodeStatus';
import './AppLayout.css';

export const AppLayout: React.FC = () => {
  const { node, connectionStatus } = useNodeStatus(3000);
  const prevStatusRef = useRef(connectionStatus);
  const [showReconnectedBanner, setShowReconnectedBanner] = useState(false);

  const isOffline = connectionStatus === 'DISCONNECTED' || connectionStatus === 'ERROR';

  useEffect(() => {
    // Show temporary reconnection banner when returning from offline
    if (
      (prevStatusRef.current === 'DISCONNECTED' || prevStatusRef.current === 'ERROR') &&
      connectionStatus === 'CONNECTED'
    ) {
      setShowReconnectedBanner(true);
      const timer = setTimeout(() => setShowReconnectedBanner(false), 3500);
      return () => clearTimeout(timer);
    }
    prevStatusRef.current = connectionStatus;
  }, [connectionStatus]);

  const getIndicatorStatus = (): { status: StatusType; label: string } => {
    switch (connectionStatus) {
      case 'CONNECTED':
        return { status: 'online', label: 'Backend Connected' };
      case 'CONNECTING':
        return { status: 'connecting', label: 'Connecting...' };
      case 'DISCONNECTED':
      case 'ERROR':
      default:
        return { status: 'offline', label: 'Backend Offline' };
    }
  };

  const indicator = getIndicatorStatus();

  return (
    <div className="app-container">
      {/* Top Bar */}
      <header className="top-bar">
        <div className="top-bar-brand">
          <svg
            className="top-bar-logo"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="18" cy="5" r="3" />
            <circle cx="6" cy="12" r="3" />
            <circle cx="18" cy="19" r="3" />
            <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
            <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
          </svg>
          <span>MeshDrop</span>
        </div>

        <div className="top-bar-actions">
          {node && connectionStatus === 'CONNECTED' && (
            <span className="top-bar-node-id" title={`Node ID: ${node.nodeId}`}>
              Node: {node.displayName} ({node.nodeId.slice(0, 8)})
            </span>
          )}

          <StatusIndicator status={indicator.status} label={indicator.label} />

          <div className="top-bar-divider" aria-hidden="true" />

          <ThemeToggle />
        </div>
      </header>

      {/* Subtle Global Status Banners */}
      {isOffline && (
        <div className="global-status-banner offline" role="status">
          <div className="banner-icon-spin" aria-hidden="true" />
          <span>MeshDrop backend is unavailable. Trying to reconnect...</span>
        </div>
      )}
      {showReconnectedBanner && !isOffline && (
        <div className="global-status-banner reconnected" role="status">
          <svg className="banner-icon-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <span>MeshDrop backend connected.</span>
        </div>
      )}

      {/* Body: Sidebar + Main Content */}
      <div className="app-body">
        <aside className="app-sidebar" aria-label="Main Navigation">
          <div className="sidebar-section-title">Navigation</div>
          <nav className="sidebar-nav">
            <NavLink
              to="/"
              end
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <svg
                className="nav-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="3" width="7" height="7" />
                <rect x="14" y="3" width="7" height="7" />
                <rect x="14" y="14" width="7" height="7" />
                <rect x="3" y="14" width="7" height="7" />
              </svg>
              <span className="nav-label">Dashboard</span>
            </NavLink>

            <NavLink
              to="/peers"
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <svg
                className="nav-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
              <span className="nav-label">Peers</span>
            </NavLink>

            <NavLink
              to="/transfers"
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <svg
                className="nav-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <line x1="12" y1="5" x2="12" y2="19" />
                <polyline points="19 12 12 19 5 12" />
              </svg>
              <span className="nav-label">Transfers</span>
            </NavLink>
          </nav>

          <div className="sidebar-footer">
            <div className="sidebar-version">MeshDrop Desktop v1.0</div>
            <div className="sidebar-version">
              {isOffline ? 'Offline' : 'Connected to Engine'}
            </div>
          </div>
        </aside>

        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
