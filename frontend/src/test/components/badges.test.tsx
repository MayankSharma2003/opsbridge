import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '../../i18n';
import { PriorityBadge } from '../../components/PriorityBadge';
import { StatusBadge } from '../../components/StatusBadge';
import styles from '../../components/badges.module.css';
import type { IncidentStatus, Priority } from '../../types/incident';

// Page tests check label text incidentally; this pins the enum -> colour mapping itself.
describe('PriorityBadge', () => {
  const cases: [Priority, string, string][] = [
    ['P1', 'crit-fill', 'P1'],
    ['P2', 'crit-outline', 'P2'],
    ['P3', 'risk-outline', 'P3'],
    ['P4', 'neutral-outline', 'P4'],
  ];

  it.each(cases)('renders %s with its own emphasis', (priority, expectedClass, label) => {
    const { container } = render(<PriorityBadge priority={priority} />);

    expect(screen.getByText(label)).toBeInTheDocument();
    expect(container.firstElementChild?.className).toContain(styles[expectedClass]);
  });

  it('escalates emphasis as priority rises, rather than reusing one style', () => {
    const rendered = cases.map(([priority]) => {
      const { container } = render(<PriorityBadge priority={priority} />);
      return container.firstElementChild?.className;
    });

    expect(new Set(rendered).size).toBe(4);
  });
});

describe('StatusBadge', () => {
  const cases: [IncidentStatus, string, string][] = [
    ['OPEN', 'info', 'Open'],
    ['TRIAGED', 'info', 'Triaged'],
    ['INVESTIGATING', 'info', 'Investigating'],
    ['MITIGATED', 'risk', 'Mitigated'],
    ['RESOLVED', 'ok', 'Resolved'],
    ['CANCELLED', 'neutral', 'Cancelled'],
  ];

  it.each(cases)('renders %s in its category colour', (status, kind, label) => {
    const { container } = render(<StatusBadge status={status} />);

    expect(screen.getByText(label)).toBeInTheDocument();
    expect(container.firstElementChild?.className).toContain(styles[kind]);
  });

  it('shows a translated label, never the raw enum', () => {
    render(<StatusBadge status="INVESTIGATING" />);

    expect(screen.queryByText('INVESTIGATING')).not.toBeInTheDocument();
    expect(screen.getByText('Investigating')).toBeInTheDocument();
  });

  it('pairs each label with a matching status dot', () => {
    const { container } = render(<StatusBadge status="RESOLVED" />);

    expect(container.querySelector(`.${styles.dot}`)?.className).toContain(styles['dot-ok']);
  });
});
