import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import '../../i18n';
import { FormDialog } from '../../components/FormDialog';

// The shell both forms sit in; its Escape/scrim/click-inside dismissal paths aren't reachable from page-level tests.
function renderDialog(overrides: Partial<Parameters<typeof FormDialog>[0]> = {}) {
  const onSubmit = vi.fn();
  const onClose = vi.fn();
  render(
    <FormDialog
      title="Declare an incident"
      submitLabel="Declare"
      pending={false}
      onSubmit={onSubmit}
      onClose={onClose}
      {...overrides}
    >
      <input aria-label="Title" />
    </FormDialog>,
  );
  return { onSubmit, onClose };
}

describe('FormDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('exposes itself as a modal dialog labelled by its title', () => {
    renderDialog();

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Declare an incident');
  });

  it('closes on Escape', () => {
    const { onClose } = renderDialog();

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('ignores other keys', () => {
    const { onClose } = renderDialog();

    fireEvent.keyDown(window, { key: 'Enter' });
    fireEvent.keyDown(window, { key: 'a' });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('stops listening for Escape once unmounted', () => {
    const onClose = vi.fn();
    const { unmount } = render(
      <FormDialog title="t" submitLabel="s" pending={false} onSubmit={vi.fn()} onClose={onClose}>
        <span />
      </FormDialog>,
    );

    unmount();
    fireEvent.keyDown(window, { key: 'Escape' });

    // A leaked listener would keep closing a dialog no longer on screen.
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes when the scrim behind the panel is clicked', () => {
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole('dialog').parentElement!);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not close when a click lands inside the panel', () => {
    const { onClose } = renderDialog();

    // Without stopPropagation, typing in a field would dismiss the form.
    fireEvent.click(screen.getByLabelText('Title'));
    fireEvent.click(screen.getByRole('dialog'));

    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes from both the header ✕ and the footer Cancel', () => {
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('submits through the form so Enter in a field works, not just the button', () => {
    const { onSubmit } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: 'Declare' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('disables the submit button while a save is in flight', () => {
    renderDialog({ pending: true, submitLabel: 'Declaring…' });

    expect(screen.getByRole('button', { name: 'Declaring…' })).toBeDisabled();
  });

  it('renders a translated store error above the fields', () => {
    renderDialog({ error: 'errors.forbidden' });

    expect(screen.getByRole('alert')).toHaveTextContent("Your role can't do that.");
  });

  it('shows no alert region when there is no error', () => {
    renderDialog();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
