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

function renderFieldInput(field: FormField, value: string, onChange: (nextValue: string) => void) {
  if (field.fieldType === 'SELECT' || field.fieldType === 'CURRENCY') {
    return (
      <select className="brand-select" value={value} onChange={(event) => onChange(event.target.value)}>
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
    />
  );
}

function SelectableListCard({
  messageId,
  block,
  onSubmit,
}: {
  messageId: string;
  block: SelectableListBlock;
  onSubmit: SubmitHandler;
}) {
  return (
    <div className="brand-panel p-5">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <span className="text-xs uppercase tracking-[0.14em] text-brand-gray">{block.selectionMode}</span>
      </div>
      <div className="space-y-3">
        {block.items.map((item) => (
          <button
            key={item.itemId}
            className="group w-full border border-brand-line bg-white px-4 py-4 text-left transition hover:border-brand-red hover:bg-[#fff4f5]"
            onClick={() =>
              onSubmit({
                eventType: 'SELECT_ITEM',
                sourceMessageId: messageId,
                sourceBlockId: block.blockId,
                selectedItemIds: [item.itemId],
              })
            }
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
}: {
  messageId: string;
  block: SimpleFormBlock;
  onSubmit: SubmitHandler;
}) {
  const [values, setValues] = useState<Record<string, string>>({});

  return (
    <div className="brand-panel p-5">
      <h4 className="mb-5 text-base font-semibold text-brand-black">{block.title}</h4>
      <div className="grid gap-4 md:grid-cols-2">
        {block.fields.map((field) => (
          <label key={field.fieldId} className="block">
            <span className="mb-2 block text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">
              {field.label}
              {field.required ? ' *' : ''}
            </span>
            {renderFieldInput(field, values[field.fieldId] ?? '', (nextValue) =>
              setValues((current) => ({ ...current, [field.fieldId]: nextValue })),
            )}
          </label>
        ))}
      </div>
      <div className="mt-5 flex justify-end">
        <BrandButton
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
}: {
  messageId: string;
  block: SummaryCardBlock;
  onSubmit: SubmitHandler;
}) {
  const actions = blockStageActionList(block);

  return (
    <div className="brand-panel p-5">
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
              onClick={() =>
                onSubmit({
                  eventType: 'CLICK_ACTION',
                  sourceMessageId: messageId,
                  sourceBlockId: block.blockId,
                  selectedItemIds: [action.id],
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

function TextCard({ title, text, accent = 'black' }: { title?: string | null; text: string; accent?: 'black' | 'red' }) {
  return (
    <div className={cn('border bg-white px-5 py-4', accent === 'red' ? 'border-red-700' : 'border-brand-line')}>
      {title ? <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">{title}</p> : null}
      <p className="text-sm leading-7 text-brand-black">{text}</p>
    </div>
  );
}

export function StructuredBlock({
  messageId,
  block,
  onSubmit,
}: {
  messageId: string;
  block: ContentBlock;
  onSubmit: SubmitHandler;
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
    return <SummaryCardView messageId={messageId} block={block} onSubmit={onSubmit} />;
  }

  if (block.type === 'SELECTABLE_LIST') {
    return <SelectableListCard messageId={messageId} block={block} onSubmit={onSubmit} />;
  }

  return <SimpleFormCard messageId={messageId} block={block} onSubmit={onSubmit} />;
}
