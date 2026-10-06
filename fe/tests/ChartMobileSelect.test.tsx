import { useState } from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import JPSelect from '../src/components/JPSelect';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const chartCss = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../src/pages/ChartPage.css'), 'utf8');

const options = [
  { label: '카페', value: '카페' },
  { label: '마트 / 편의점', value: '마트 / 편의점' },
];
afterEach(cleanup);

describe('Chart mobile Select presentation contract', () => {
  it('preserves the default Select label, current value and unscoped portal for other screens', async () => {
    render(<JPSelect value="카페" options={options} onChange={() => {}} />);
    const trigger = screen.getByRole('combobox', { name: '선택' });
    expect(trigger).toHaveTextContent('카페');
    await userEvent.setup().click(trigger);
    expect(screen.getByRole('listbox')).not.toHaveClass('jp-chart-select-content');
    expect(screen.getByRole('option', { name: '카페' })).toBeInTheDocument();
  });

  it('keeps a labeled real combobox and its scoped portal accessible while changing category', async () => {
    const changed = vi.fn();
    function Example() {
      const [value, setValue] = useState('카페');
      return <JPSelect value={value} options={options} ariaLabel="분류 연습 거래 카테고리"
        className="jp-chart-select" contentClassName="jp-chart-select-content"
        onChange={next => { changed(next); setValue(next); }} />;
    }
    render(<Example />);
    const user = userEvent.setup();
    const trigger = screen.getByRole('combobox', { name: '분류 연습 거래 카테고리' });
    expect(trigger).toHaveTextContent('카페');
    await user.click(trigger);
    const listbox = screen.getByRole('listbox');
    expect(listbox).toHaveClass('jp-chart-select-content');
    await user.click(within(listbox).getByRole('option', { name: '마트 / 편의점' }));
    expect(changed).toHaveBeenCalledExactlyOnceWith('마트 / 편의점');
    expect(trigger).toHaveTextContent('마트 / 편의점');
  });

  it('keeps a disabled Chart Select non-editable without removing the current category', async () => {
    const changed = vi.fn();
    render(<JPSelect value="카페" options={options} disabled ariaLabel="분류 연습 거래 카테고리"
      className="jp-chart-select" contentClassName="jp-chart-select-content" onChange={changed} />);
    const trigger = screen.getByRole('combobox', { name: '분류 연습 거래 카테고리' });
    expect(trigger).toBeDisabled();
    expect(trigger).toHaveTextContent('카페');
    await userEvent.setup().click(trigger);
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });

  it('keeps desktop detail grid, mobile stacked rows and natural page scrolling', () => {
    // CSS structure is a regression guard only; actual viewport geometry is
    // verified separately by Chromium against the real application.
    const [desktop, mobile] = chartCss.split('@media (max-width: 900px)');
    expect(desktop).toMatch(/\.jp-grid\s*\{[^}]*grid-template-columns:\s*1\.25fr 1\.25fr/s);
    expect(mobile).toBeDefined();
    expect(mobile).toMatch(/\.jp-grid\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\)/s);
    expect(mobile).toMatch(/#screen2\.jp-detail-screen\s*\{[^}]*overflow-y:\s*auto/s);
    // The heading and pond now use normal vertical flow inside the page's
    // scroll container at every width; screen1 must not clip or shrink content.
    expect(desktop).toMatch(/\.jp-wrap\s*\{[^}]*overflow-y:\s*auto/s);
    expect(desktop).toMatch(/#screen1\.jp-screen\s*\{[^}]*flex-direction:\s*column/s);
    const screenLayout = desktop.match(/#screen1\.jp-screen\s*\{([^}]*)\}/s)?.[1];
    expect(screenLayout).toBeDefined();
    expect(screenLayout).not.toMatch(/(?:^|;)\s*height:|overflow(?:-x|-y)?:\s*(?:hidden|clip)/);
    expect(mobile).toMatch(/\.jp-table tbody tr\s*\{[^}]*display:\s*grid/s);
    expect(mobile).toMatch(/\.jp-chart-select[^}]*min-height:\s*44px/s);
    expect(mobile).not.toMatch(/overflow(?:-x)?:\s*(?:hidden|clip)|transform:\s*scale/);
  });
});
