import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ContentBlock } from '@/shared/api/contracts';
import { StructuredBlock } from '@/features/ui-events/StructuredBlocks';

afterEach(() => cleanup());

describe('StructuredBlock', () => {
  it('renders registered payee lookup results as payee cards', () => {
    const block: ContentBlock = {
      blockId: 'blk_summary_1',
      type: 'SUMMARY_CARD',
      title: 'Registered payee results',
      fields: [
        { label: 'Match 1', value: 'Alice Chan • Test Bank • Current - 123456' },
        { label: 'Match 2', value: 'Bob Lee • Second Bank • Savings - 998877' },
      ],
      metadata: {
        purpose: 'registered-payee-results',
        payeeCount: 2,
        payees: [
          {
            payeeId: 'payee_1',
            name: 'Alice Chan',
            payeeType: 'DOMESTIC',
            bankCode: '004',
            bankName: 'Test Bank',
            accountNumber: '123456',
            displayLabel: 'Current - 123456',
          },
          {
            payeeId: 'payee_2',
            name: 'Bob Lee',
            bankName: 'Second Bank',
            displayLabel: 'Savings - 998877',
          },
        ],
      },
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={vi.fn()} />);

    expect(screen.getByText('Registered payee results')).toBeInTheDocument();
    expect(screen.getByText('2 payees')).toBeInTheDocument();
    expect(screen.getByText('Alice Chan')).toBeInTheDocument();
    expect(screen.getByText('Test Bank')).toBeInTheDocument();
    expect(screen.getByText('Current - 123456')).toBeInTheDocument();
    expect(screen.getByText('Payee 01')).toBeInTheDocument();
    expect(screen.queryByText('DOMESTIC')).not.toBeInTheDocument();
  });

  it('renders payee selection cards and submits the selected item', () => {
    const onSubmit = vi.fn();
    const block: ContentBlock = {
      blockId: 'blk_list_1',
      type: 'SELECTABLE_LIST',
      title: 'Registered payee matches',
      metadata: { purpose: 'payee-selection' },
      items: [
        {
          itemId: 'payee_1',
          label: 'Alice Chan',
          description: 'Test Bank • Current - 123456',
          metadata: {
            name: 'Alice Chan',
            bankName: 'Test Bank',
            displayLabel: 'Current - 123456',
          },
        },
      ],
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole('button', { name: /Alice Chan/i }));

    expect(onSubmit).toHaveBeenCalledWith({
      eventType: 'SELECT_ITEM',
      sourceMessageId: 'msg_1',
      sourceBlockId: 'blk_list_1',
      selectedItemId: 'payee_1',
    });
  });

  it('does not submit selectable items while disabled', () => {
    const onSubmit = vi.fn();
    const block: ContentBlock = {
      blockId: 'blk_list_2',
      type: 'SELECTABLE_LIST',
      title: 'Registered payee matches',
      metadata: { purpose: 'payee-selection' },
      items: [
        {
          itemId: 'payee_1',
          label: 'Alice Chan',
          description: 'Test Bank • Current - 123456',
          metadata: {
            name: 'Alice Chan',
            bankName: 'Test Bank',
            displayLabel: 'Current - 123456',
          },
        },
      ],
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={onSubmit} disabled />);

    const button = screen.getByRole('button', { name: /Alice Chan/i });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('highlights pending summary fields', () => {
    const block: ContentBlock = {
      blockId: 'blk_summary_2',
      type: 'SUMMARY_CARD',
      title: 'Current draft',
      fields: [
        { label: 'Payee', value: 'Alice Chan' },
        { label: 'Amount', value: 'Pending' },
        { label: 'Payment date', value: 'Pending' },
      ],
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={vi.fn()} />);

    expect(screen.getAllByText('Needed')).toHaveLength(2);
  });

  it('renders a date picker for editable payment-date fields and submits the picked value on confirm', () => {
    const onSubmit = vi.fn();
    const block: ContentBlock = {
      blockId: 'blk_summary_confirm',
      type: 'SUMMARY_CARD',
      title: 'Domestic payment summary',
      fields: [
        { label: 'Payee', value: 'Alice Chan' },
        { label: 'Amount', value: 'HKD 100.00' },
        { label: 'Payment date', value: '2026-04-27' },
      ],
      metadata: {
        actions: [
          { id: 'CONFIRM_PAYMENT', label: 'Confirm payment' },
          { id: 'CANCEL_PAYMENT', label: 'Cancel', tone: 'secondary' },
        ],
        editableFields: [
          {
            label: 'Payment date',
            fieldId: 'paymentDate',
            fieldType: 'DATE',
            value: '2026-04-27',
            minDate: '2026-04-27',
          },
        ],
      },
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={onSubmit} />);

    const dateInput = screen.getByDisplayValue('2026-04-27') as HTMLInputElement;
    expect(dateInput.type).toBe('date');
    fireEvent.change(dateInput, { target: { value: '2026-04-30' } });

    fireEvent.click(screen.getByRole('button', { name: 'Confirm payment' }));

    expect(onSubmit).toHaveBeenCalledWith({
      eventType: 'CLICK_ACTION',
      sourceMessageId: 'msg_1',
      sourceBlockId: 'blk_summary_confirm',
      actionValue: 'CONFIRM_PAYMENT',
      formValues: { paymentDate: '2026-04-30' },
    });
  });

  it('renders a date picker for a pending payment-date field and auto-submits SUBMIT_FORM on change', () => {
    const onSubmit = vi.fn();
    const block: ContentBlock = {
      blockId: 'blk_summary_pending',
      type: 'SUMMARY_CARD',
      title: 'Current draft',
      fields: [
        { label: 'Payee', value: 'Alice Chan' },
        { label: 'Amount', value: 'HKD 100.00' },
        { label: 'Payment date', value: 'Pending' },
      ],
      metadata: {
        editableFields: [
          {
            label: 'Payment date',
            fieldId: 'paymentDate',
            fieldType: 'DATE',
            value: '',
            minDate: '2026-04-27',
            submitOnChange: true,
          },
        ],
      },
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={onSubmit} />);

    expect(screen.queryByText('Pending')).not.toBeInTheDocument();
    expect(screen.queryByText('Needed')).not.toBeInTheDocument();
    const dateInputs = screen.getAllByDisplayValue('');
    const dateInput = dateInputs.find((node) => (node as HTMLInputElement).type === 'date') as HTMLInputElement;
    expect(dateInput).toBeDefined();

    fireEvent.change(dateInput, { target: { value: '2026-05-15' } });

    expect(onSubmit).toHaveBeenCalledWith({
      eventType: 'SUBMIT_FORM',
      sourceMessageId: 'msg_1',
      sourceBlockId: 'blk_summary_pending',
      formValues: { paymentDate: '2026-05-15' },
    });
  });

  it('disables summary actions without showing a loading state', () => {
    const block: ContentBlock = {
      blockId: 'blk_summary_3',
      type: 'SUMMARY_CARD',
      title: 'Domestic payment summary',
      fields: [{ label: 'Payee', value: 'Alice Chan' }],
      metadata: {
        actions: [
          { id: 'CONFIRM_PAYMENT', label: 'Confirm payment' },
          { id: 'CANCEL_PAYMENT', label: 'Cancel', tone: 'secondary' },
        ],
      },
    };

    render(<StructuredBlock messageId="msg_1" block={block} onSubmit={vi.fn()} disabled />);

    const confirm = screen.getByRole('button', { name: 'Confirm payment' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(confirm).toBeDisabled();
    expect(cancel).toBeDisabled();
    expect(confirm).toHaveAttribute('aria-busy', 'false');
    expect(cancel).toHaveAttribute('aria-busy', 'false');
  });
});
