import React, { useEffect, useRef, useState } from 'react';
import { Badge } from '../Badge/Badge';
import { Button } from '../Button/Button';
import { Peer } from '../../types/Peer';
import { formatBytes } from '../../utils/formatters';
import './SendFileDialog.css';

export interface SendFileDialogProps {
  isOpen: boolean;
  onClose: () => void;
  peers: Peer[];
  defaultPeerId?: string;
  onSend: (peerId: string, filePath: string) => Promise<void>;
}

export const SendFileDialog: React.FC<SendFileDialogProps> = ({
  isOpen,
  onClose,
  peers,
  defaultPeerId,
  onSend,
}) => {
  const [selectedFileMeta, setSelectedFileMeta] = useState<{
    name: string;
    size: number;
    type: string;
  } | null>(null);
  const [filePath, setFilePath] = useState<string>('');
  const [selectedPeerId, setSelectedPeerId] = useState<string>('');
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  const connectedPeers = peers.filter((p) => p.connected || p.state === 'CONNECTED');

  // Pre-select peer when dialog opens
  useEffect(() => {
    if (isOpen) {
      if (defaultPeerId) {
        setSelectedPeerId(defaultPeerId);
      } else if (connectedPeers.length > 0 && !selectedPeerId) {
        setSelectedPeerId(connectedPeers[0].id);
      }
      setErrorMessage(null);
    }
  }, [isOpen, defaultPeerId, connectedPeers, selectedPeerId]);

  // Handle ESC key dismiss
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isSubmitting) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isSubmitting, onClose]);

  if (!isOpen) return null;

  const handleFilePickerChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      // Memory safe: extract only metadata, DO NOT read file bytes into memory!
      setSelectedFileMeta({
        name: file.name,
        size: file.size,
        type: file.type || 'application/octet-stream',
      });
      // Suggest a path based on file name or data folder
      if (!filePath || filePath.endsWith('\\') || filePath.endsWith('/')) {
        setFilePath(`data\\${file.name}`);
      } else {
        setFilePath(file.name);
      }
      setErrorMessage(null);
    }
  };

  const handleApplyPreset = (presetPath: string, presetName: string, presetSize: number) => {
    setSelectedFileMeta({
      name: presetName,
      size: presetSize,
      type: 'application/octet-stream',
    });
    setFilePath(presetPath);
    setErrorMessage(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPeerId) {
      setErrorMessage('Please select a connected recipient peer.');
      return;
    }
    if (!filePath.trim()) {
      setErrorMessage('Please specify a local file path on disk.');
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    try {
      await onSend(selectedPeerId, filePath.trim());
      // Reset and close on success
      setSelectedFileMeta(null);
      setFilePath('');
      onClose();
    } catch (err: unknown) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setIsSubmitting(false);
    }
  };

  const targetPeer = peers.find((p) => p.id === selectedPeerId);

  return (
    <div className="modal-backdrop" onClick={!isSubmitting ? onClose : undefined} role="presentation">
      <div
        className="send-file-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="send-file-dialog-title"
        ref={dialogRef}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="dialog-header">
          <h2 id="send-file-dialog-title">Send File to Peer</h2>
          <button
            type="button"
            className="dialog-close-btn"
            onClick={onClose}
            disabled={isSubmitting}
            aria-label="Close dialog"
          >
            &times;
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="dialog-body">
            {errorMessage && (
              <div className="dialog-error-banner" role="alert">
                {errorMessage}
              </div>
            )}

            {/* Section 1: File Selection */}
            <div className="dialog-section">
              <label className="section-label">1. Select Local File</label>

              <div className="file-selection-controls">
                <input
                  type="file"
                  ref={fileInputRef}
                  style={{ display: 'none' }}
                  onChange={handleFilePickerChange}
                />
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isSubmitting}
                >
                  Browse Local Files...
                </Button>

                {/* Quick preset for demo / verification */}
                <button
                  type="button"
                  className="quick-preset-btn"
                  onClick={() =>
                    handleApplyPreset(
                      'C:\\Users\\VBP\\Desktop\\SocketStuff\\data\\test500mb.dat',
                      'test500mb.dat',
                      524288000
                    )
                  }
                  title="Quick shortcut for 500 MB verification file"
                >
                  500 MB Demo File
                </button>
              </div>

              {selectedFileMeta ? (
                <div className="selected-file-card">
                  <div className="selected-file-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                      <polyline points="14 2 14 8 20 8" />
                    </svg>
                  </div>
                  <div className="selected-file-details">
                    <span className="selected-file-name" title={selectedFileMeta.name}>
                      {selectedFileMeta.name}
                    </span>
                    <span className="selected-file-subtext">
                      {formatBytes(selectedFileMeta.size)} &bull; {selectedFileMeta.type}
                    </span>
                  </div>
                </div>
              ) : (
                <div className="file-picker-placeholder">
                  No file chosen yet. Click &quot;Browse Local Files&quot; or enter a path below.
                </div>
              )}

              <div className="path-input-group">
                <label htmlFor="file-path-input" className="sub-label">
                  Local File Path on Disk:
                </label>
                <input
                  id="file-path-input"
                  type="text"
                  className="dialog-text-input"
                  placeholder="e.g. data\test500mb.dat or C:\path\to\file.ext"
                  value={filePath}
                  onChange={(e) => setFilePath(e.target.value)}
                  disabled={isSubmitting}
                  required
                />
                <span className="input-hint">
                  The Java engine streams this file directly from disk without browser memory overhead.
                </span>
              </div>
            </div>

            {/* Section 2: Peer Selection */}
            <div className="dialog-section">
              <label className="section-label">2. Destination Peer</label>
              {connectedPeers.length === 0 ? (
                <div className="no-peers-warning">
                  No connected peers available. Please connect to a peer first on the Peers page.
                </div>
              ) : (
                <div className="peers-selector-list" role="radiogroup" aria-label="Connected peers">
                  {connectedPeers.map((peer) => {
                    const isSelected = peer.id === selectedPeerId;
                    return (
                      <div
                        key={peer.id}
                        role="radio"
                        aria-checked={isSelected}
                        tabIndex={0}
                        className={`peer-select-card ${isSelected ? 'selected' : ''}`}
                        onClick={() => setSelectedPeerId(peer.id)}
                        onKeyDown={(e) => {
                          if (e.key === ' ' || e.key === 'Enter') {
                            setSelectedPeerId(peer.id);
                          }
                        }}
                      >
                        <div className="peer-select-radio">
                          <input
                            type="radio"
                            name="targetPeer"
                            checked={isSelected}
                            onChange={() => setSelectedPeerId(peer.id)}
                            aria-label={peer.displayName}
                          />
                        </div>
                        <div className="peer-select-info">
                          <div className="peer-select-title">
                            <strong>{peer.displayName}</strong>
                            <Badge variant="success" withDot>
                              READY
                            </Badge>
                          </div>
                          <span className="peer-select-address">
                            {peer.address ? `${peer.address}:${peer.port}` : 'Local Node'}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Section 3: Confirmation Summary */}
            {selectedFileMeta && targetPeer && (
              <div className="transfer-summary-panel">
                <h4 className="summary-title">Transfer Confirmation</h4>
                <div className="summary-row">
                  <span className="summary-label">File:</span>
                  <span className="summary-val">{selectedFileMeta.name}</span>
                </div>
                <div className="summary-row">
                  <span className="summary-label">Size:</span>
                  <span className="summary-val">{formatBytes(selectedFileMeta.size)}</span>
                </div>
                <div className="summary-row">
                  <span className="summary-label">Destination:</span>
                  <span className="summary-val font-semibold">{targetPeer.displayName}</span>
                </div>
              </div>
            )}
          </div>

          <div className="dialog-footer">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={onClose}
              disabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              size="sm"
              disabled={isSubmitting || connectedPeers.length === 0 || !filePath.trim()}
            >
              {isSubmitting ? 'Starting Transfer...' : 'Send File'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};
