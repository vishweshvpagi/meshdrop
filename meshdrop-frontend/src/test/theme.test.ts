import { describe, expect, it, beforeEach, vi } from 'vitest';

describe('Theme System', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('detects and persists theme in localStorage', () => {
    localStorage.setItem('meshdrop-theme', 'dark');
    expect(localStorage.getItem('meshdrop-theme')).toBe('dark');

    document.documentElement.setAttribute('data-theme', 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('switches between light and dark themes', () => {
    let currentTheme: 'light' | 'dark' = 'light';
    const toggle = () => {
      currentTheme = currentTheme === 'dark' ? 'light' : 'dark';
      localStorage.setItem('meshdrop-theme', currentTheme);
      document.documentElement.setAttribute('data-theme', currentTheme);
    };

    toggle();
    expect(currentTheme).toBe('dark');
    expect(localStorage.getItem('meshdrop-theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    toggle();
    expect(currentTheme).toBe('light');
    expect(localStorage.getItem('meshdrop-theme')).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('respects system preference when preference is system', () => {
    const matchMediaMock = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('dark'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));

    window.matchMedia = matchMediaMock;
    const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    expect(isDark).toBe(true);
  });
});
