import { useState } from 'react';
import type {
  ContentBlock,
  FormField,
  SelectableListBlock,
  SimpleFormBlock,
  SummaryCardBlock,
  UiEventRequest,
} from '@/shared/api/contracts';
import { BrandButton } from '@/shared/ui/BrandButton';
import { cn } from '@/shared/lib/cn';

type SubmitHandler = (request: UiEventRequest) => void;

function blockStageActionList(block: SummaryCardBlock) {
  const actions = block.metadata?.actions;
  return Array.isArray(actions)
    ? actions.filter(
        (item): item is { id: string; label: string; tone?: string } =>
          typeof item === 'object' &&
          item !== null &&
          'id' in item &&
          'label' in item &&
          typeof item.id === 'string' &&
          typeof item.label === 'string',
      )
    : [];
}

function renderFieldInput(
  field: FormField,
  value: string,
  onChange: (nextValue: string) => void,
  disabled = false,
) {
  if (field.fieldType === 'SELECT' || field.fieldType === 'CURRENCY') {
    return (
      <select
        className="brand-select"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        <option value="">Select</option>
        {field.options?.map((option) => (
          <option key={option.itemId} value={option.itemId}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  return (
    <input
      className="brand-input"
      type={field.fieldType === 'NUMBER' ? 'number' : 'text'}
      placeholder={field.placeholder ?? ''}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      disabled={disabled}
    />
  );
}

function SelectableListCard({
  messageId,
  block,
  onSubmit,
  disabled,
}: {
  messageId: string;
  block: SelectableListBlock;
  onSubmit: SubmitHandler;
  disabled?: boolean;
}) {
  return (
    <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <span className="text-xs uppercase tracking-[0.14em] text-brand-gray">Select one</span>
      </div>
      <div className="space-y-3">
        {block.items.map((item) => (
          <button
            key={item.itemId}
            className={cn(
              'group w-full border border-brand-line bg-white px-4 py-4 text-left transition',
              disabled ? 'cursor-not-allowed opacity-60' : 'hover:border-brand-red hover:bg-[#fff4f5]',
            )}
            onClick={() =>
              onSubmit({
                eventType: 'SELECT_ITEM',
                sourceMessageId: messageId,
                sourceBlockId: block.blockId,
                selectedItemId: item.itemId,
              })
            }
            disabled={disabled}
          >
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-sm font-semibold text-brand-black">{item.label}</p>
                {item.description ? (
                  <p className="mt-1 text-sm leading-6 text-brand-gray">{item.description}</p>
                ) : null}
              </div>
              <span className="mt-1 h-5 w-5 border border-brand-black transition group-hover:border-brand-red" />
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}

function SimpleFormCard({
  messageId,
  block,
  onSubmit,
  disabled,
}: {
  messageId: string;
  block: SimpleFormBlock;
  onSubmit: SubmitHandler;
  disabled?: boolean;
}) {
  const [values, setValues] = useState<Record<string, string>>({});

  return (
    <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
      <h4 className="mb-5 text-base font-semibold text-brand-black">{block.title}</h4>
      <div className="grid gap-4 md:grid-cols-2">
        {block.fields.map((field) => (
          <label key={field.fieldId} className="block">
            <span className="mb-2 block text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">
              {field.label}
              {field.required ? ' *' : ''}
            </span>
            {renderFieldInput(
              field,
              values[field.fieldId] ?? '',
              (nextValue) => setValues((current) => ({ ...current, [field.fieldId]: nextValue })),
              disabled,
            )}
          </label>
        ))}
      </div>
      <div className="mt-5 flex justify-end">
        <BrandButton
          disabled={disabled}
          loading={Boolean(disabled)}
          onClick={() =>
            onSubmit({
              eventType: 'SUBMIT_FORM',
              sourceMessageId: messageId,
              sourceBlockId: block.blockId,
              formValues: values,
            })
          }
        >
          {block.submitLabel ?? 'Submit'}
        </BrandButton>
      </div>
    </div>
  );
}

function SummaryCardView({
  messageId,
  block,
  onSubmit,
  disabled,
}: {
  messageId: string;
  block: SummaryCardBlock;
  onSubmit: SubmitHandler;
  disabled?: boolean;
}) {
  const actions = blockStageActionList(block);

  return (
    <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
      <div className="mb-5 flex items-center justify-between gap-4">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <div className="h-1 w-16 bg-brand-red" />
      </div>
      <div className="grid gap-3">
        {block.fields.map((field) => (
          <div key={`${field.label}-${field.value}`} className="grid gap-1 border-b border-brand-line pb-3 md:grid-cols-[160px_1fr]">
            <p className="text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">{field.label}</p>
            <p className="text-sm font-medium text-brand-black">{field.value}</p>
          </div>
        ))}
      </div>
      {actions.length ? (
        <div className="mt-5 flex flex-wrap gap-3">
          {actions.map((action) => (
            <BrandButton
              key={action.id}
              variant={action.tone === 'secondary' ? 'secondary' : 'primary'}
              disabled={disabled}
              loading={Boolean(disabled)}
              onClick={() =>
                onSubmit({
                  eventType: 'CLICK_ACTION',
                  sourceMessageId: messageId,
                  sourceBlockId: block.blockId,
                  actionValue: action.id,
                })
              }
            >
              {action.label}
            </BrandButton>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function PaymentStatusMark({ status }: { status: 'success' | 'failure' }) {
  return (
    <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center" aria-hidden="true">
      <svg viewBox="0 0 24 24" className="h-5 w-5">
        <rect x="1.5" y="1.5" width="21" height="21" fill="#111111" />
        <rect x="4" y="4" width="16" height="16" fill="#DB0011" />
        <path d="M4 4L12 12L4 20V4Z" fill="#FFFFFF" />
        <path d="M20 4L12 12L20 20V4Z" fill="#FFFFFF" />
        <path d="M4 4L12 12L20 4H4Z" fill="#DB0011" />
        <path d="M4 20L12 12L20 20H4Z" fill="#DB0011" />
        {status === 'success' ? (
          <path
            d="M7.7 12.1L10.8 15.2L16.8 8.8"
            fill="none"
            stroke="#111111"
            strokeWidth="2.2"
            strokeLinecap="square"
            strokeLinejoin="miter"
          />
        ) : (
          <>
            <path d="M8.2 8.2L15.8 15.8" stroke="#111111" strokeWidth="2.2" strokeLinecap="square" />
            <path d="M15.8 8.2L8.2 15.8" stroke="#111111" strokeWidth="2.2" strokeLinecap="square" />
          </>
        )}
      </svg>
    </span>
  );
}

function normalizeTitle(title?: string | null) {
  return title?.replace(/^✅\s*/, '') ?? null;
}

function TextCard({ title, text, accent = 'black' }: { title?: string | null; text: string; accent?: 'black' | 'red' }) {
  const displayTitle = normalizeTitle(title);
  const normalizedTitle = displayTitle?.toLowerCase() ?? '';
  const paymentStatus =
    normalizedTitle === 'payment submitted' ? 'success' : accent === 'red' ? 'failure' : null;

  return (
    <div
      className={cn(
        'border bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] px-5 py-4',
        accent === 'red' ? 'border-red-700' : 'border-brand-line',
      )}
    >
      {displayTitle ? (
        <div className="mb-2 flex items-center gap-2">
          {paymentStatus ? <PaymentStatusMark status={paymentStatus} /> : null}
          <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">{displayTitle}</p>
        </div>
      ) : null}
      <p className="text-sm leading-7 text-brand-black">{text}</p>
    </div>
  );
}

export function StructuredBlock({
  messageId,
  block,
  onSubmit,
  disabled = false,
}: {
  messageId: string;
  block: ContentBlock;
  onSubmit: SubmitHandler;
  disabled?: boolean;
}) {
  if (block.type === 'TEXT') {
    return <TextCard title={block.title} text={block.text} />;
  }

  if (block.type === 'INFO_CARD') {
    return <TextCard title={block.title} text={block.text} />;
  }

  if (block.type === 'ERROR_CARD') {
    return <TextCard title={block.title} text={block.text} accent="red" />;
  }

  if (block.type === 'SUMMARY_CARD') {
    return <SummaryCardView messageId={messageId} block={block} onSubmit={onSubmit} disabled={disabled} />;
  }

  if (block.type === 'SELECTABLE_LIST') {
    return <SelectableListCard messageId={messageId} block={block} onSubmit={onSubmit} disabled={disabled} />;
  }

  return <SimpleFormCard messageId={messageId} block={block} onSubmit={onSubmit} disabled={disabled} />;
}
