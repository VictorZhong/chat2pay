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

type PayeeCardData = {
  itemId?: string;
  index?: number;
  name: string;
  payeeType?: string | null;
  bankCode?: string | null;
  bankName?: string | null;
  accountNumber?: string | null;
  displayLabel?: string | null;
  descriptor?: string | null;
};

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function cleanText(value: unknown) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
}

function metadataText(metadata: Record<string, unknown> | null | undefined, ...keys: string[]) {
  for (const key of keys) {
    const value = cleanText(metadata?.[key]);
    if (value) return value;
  }
  return null;
}

function payeeFromMetadata(
  metadata: Record<string, unknown> | null | undefined,
  fallback: Partial<PayeeCardData>,
  index: number,
): PayeeCardData {
  return {
    itemId: metadataText(metadata, 'payeeId', 'payee_id') ?? fallback.itemId,
    index: fallback.index ?? index + 1,
    name: metadataText(metadata, 'name') ?? fallback.name ?? `Payee ${index + 1}`,
    payeeType: metadataText(metadata, 'payeeType', 'payee_type') ?? fallback.payeeType ?? null,
    bankCode: metadataText(metadata, 'bankCode', 'bank_code') ?? fallback.bankCode ?? null,
    bankName: metadataText(metadata, 'bankName', 'bank_name') ?? fallback.bankName ?? null,
    accountNumber:
      metadataText(metadata, 'accountNumber', 'account_number') ?? fallback.accountNumber ?? null,
    displayLabel:
      metadataText(metadata, 'displayLabel', 'display_label') ?? fallback.displayLabel ?? null,
    descriptor: fallback.descriptor ?? null,
  };
}

function payeesFromSummaryBlock(block: SummaryCardBlock): PayeeCardData[] {
  const rawPayees = block.metadata?.payees;
  if (Array.isArray(rawPayees)) {
    const parsed = rawPayees
      .map((item, index) => (isRecord(item) ? payeeFromMetadata(item, {}, index) : null))
      .filter((item): item is PayeeCardData => item !== null);
    if (parsed.length) return parsed;
  }

  return block.fields.map((field, index) => {
    const parts = field.value.split(' • ').map((part) => part.trim()).filter(Boolean);
    return {
      index: index + 1,
      name: parts[0] ?? field.label,
      bankName: parts[1] ?? null,
      displayLabel: parts.length > 2 ? parts.slice(2).join(' • ') : field.value,
    };
  });
}

function payeeFromSelectableItem(item: SelectableListBlock['items'][number], index: number) {
  const descriptionParts =
    item.description?.split(' • ').map((part) => part.trim()).filter(Boolean) ?? [];
  return payeeFromMetadata(
    item.metadata ?? null,
    {
      itemId: item.itemId,
      index: index + 1,
      name: item.label,
      bankName: descriptionParts[0] ?? null,
      displayLabel: descriptionParts.length > 1 ? descriptionParts.slice(1).join(' • ') : null,
      descriptor: item.description ?? null,
    },
    index,
  );
}

function isPayeeResultsBlock(block: SummaryCardBlock) {
  return block.metadata?.purpose === 'registered-payee-results';
}

function isPendingValue(value: string) {
  return value.trim().toLowerCase() === 'pending';
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

function PayeeDetail({ label, value }: { label: string; value?: string | null }) {
  if (!value) return null;
  return (
    <div className="grid grid-cols-[84px_1fr] gap-3">
      <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
        {label}
      </span>
      <span className="min-w-0 break-words text-sm font-medium text-brand-black">{value}</span>
    </div>
  );
}

function PayeeCardContent({ payee }: { payee: PayeeCardData }) {
  const payeeNumber = String(payee.index ?? 1).padStart(2, '0');

  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
            Payee {payeeNumber}
          </p>
          <h4 className="mt-1 break-words text-base font-semibold leading-6 text-brand-black">
            {payee.name}
          </h4>
        </div>
        {payee.payeeType ? (
          <span className="shrink-0 border border-brand-line bg-brand-fog px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-brand-gray">
            {payee.payeeType.replaceAll('_', ' ')}
          </span>
        ) : null}
      </div>

      <div className="grid gap-2">
        <PayeeDetail label="Bank" value={payee.bankName} />
        <PayeeDetail label="Code" value={payee.bankCode} />
        <PayeeDetail label="Account" value={payee.displayLabel ?? payee.accountNumber} />
        {payee.displayLabel && payee.accountNumber && payee.displayLabel !== payee.accountNumber ? (
          <PayeeDetail label="Number" value={payee.accountNumber} />
        ) : null}
        {!payee.bankName && !payee.displayLabel ? <PayeeDetail label="Details" value={payee.descriptor} /> : null}
      </div>
    </div>
  );
}

function PayeeResultsCardList({ block }: { block: SummaryCardBlock }) {
  const payees = payeesFromSummaryBlock(block);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-brand-line pb-3">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
          {payees.length} {payees.length === 1 ? 'payee' : 'payees'}
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {payees.map((payee, index) => (
          <article
            key={`${payee.itemId ?? payee.name}-${index}`}
            className="border border-brand-line bg-white p-4 shadow-[0_10px_24px_rgba(17,17,17,0.06)]"
          >
            <PayeeCardContent payee={payee} />
          </article>
        ))}
      </div>
    </div>
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
  const isPayeeSelection = block.metadata?.purpose === 'payee-selection';

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-brand-line pb-3">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
          Select one
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {block.items.map((item, index) => {
          const payee = payeeFromSelectableItem(item, index);
          return (
            <button
              key={item.itemId}
              className={cn(
                'group flex h-full w-full flex-col border border-brand-line bg-white p-4 text-left shadow-[0_10px_24px_rgba(17,17,17,0.06)] transition',
                disabled
                  ? 'cursor-not-allowed opacity-60'
                  : 'hover:border-brand-red hover:bg-[#fff8f8] hover:shadow-[0_14px_30px_rgba(219,0,17,0.08)]',
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
              {isPayeeSelection ? (
                <PayeeCardContent payee={payee} />
              ) : (
                <div>
                  <p className="break-words text-sm font-semibold text-brand-black">{item.label}</p>
                  {item.description ? (
                    <p className="mt-1 break-words text-sm leading-6 text-brand-gray">{item.description}</p>
                  ) : null}
                </div>
              )}
              <div className="mt-4 flex items-center justify-between gap-3 border-t border-brand-line pt-3">
                <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
                  Choose
                </span>
                <span className="h-5 w-5 border border-brand-black transition group-hover:border-brand-red group-hover:bg-brand-red" />
              </div>
            </button>
          );
        })}
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

  if (isPayeeResultsBlock(block)) {
    return <PayeeResultsCardList block={block} />;
  }

  return (
    <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
      <div className="mb-5 flex items-center justify-between gap-4">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <div className="h-1 w-16 bg-brand-red" />
      </div>
      <div className="grid gap-3">
        {block.fields.map((field) => {
          const pending = isPendingValue(field.value);
          return (
            <div
              key={`${field.label}-${field.value}`}
              className={cn(
                'grid gap-1 border-b border-brand-line py-3 md:grid-cols-[160px_1fr]',
                pending && 'border-l-4 border-l-brand-red bg-[#fff4f5] px-3',
              )}
            >
              <div className="flex items-center gap-2">
                <p className="text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">{field.label}</p>
                {pending ? (
                  <span className="border border-[#e8a7ad] bg-white px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-brand-red">
                    Needed
                  </span>
                ) : null}
              </div>
              <p className={cn('break-words text-sm font-medium', pending ? 'text-brand-red' : 'text-brand-black')}>
                {field.value}
              </p>
            </div>
          );
        })}
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

type AlertTone = 'success' | 'failure';

function PaymentStatusMark({ tone }: { tone: AlertTone }) {
  return (
    <span
      className={cn(
        'inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[11px] font-bold leading-none text-white',
        tone === 'success' ? 'bg-[#00847f]' : 'bg-brand-red',
      )}
      aria-hidden="true"
    >
      {tone === 'success' ? '✓' : '!'}
    </span>
  );
}

function normalizeTitle(title?: string | null) {
  return title?.replace(/^✅\s*/, '') ?? null;
}

function TextCard({ title, text, accent = 'black' }: { title?: string | null; text: string; accent?: 'black' | 'red' }) {
  const displayTitle = normalizeTitle(title);
  const normalizedTitle = displayTitle?.toLowerCase() ?? '';
  const alertTone: AlertTone | null =
    normalizedTitle === 'payment submitted' ? 'success' : accent === 'red' ? 'failure' : null;

  if (alertTone && displayTitle) {
    return (
      <div
        className={cn(
          'flex items-start gap-3 border px-5 py-4 text-sm leading-6 text-brand-black',
          alertTone === 'success' ? 'border-[#8ecfca] bg-[#e4f3f1]' : 'border-[#e8a7ad] bg-[#fff4f5]',
        )}
      >
        <PaymentStatusMark tone={alertTone} />
        <p>
          <span className="font-semibold">{displayTitle}.</span> {text}
        </p>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'border bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] px-5 py-4',
        accent === 'red' ? 'border-red-700' : 'border-brand-line',
      )}
    >
      {displayTitle ? (
        <div className="mb-2 flex items-center gap-2">
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
