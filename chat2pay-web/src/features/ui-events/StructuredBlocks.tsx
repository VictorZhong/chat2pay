import { useEffect, useState } from 'react';
import type {
  ContentBlock,
  FormField,
  SelectableItem,
  SelectableItemDetailField,
  SelectableListBlock,
  SimpleFormBlock,
  SummaryCardBlock,
  UiEventRequest,
} from '@/shared/api/contracts';
import { BrandButton } from '@/shared/ui/BrandButton';
import { CloseIcon } from '@/shared/ui/icons';
import { cn } from '@/shared/lib/cn';

type SubmitHandler = (request: UiEventRequest) => void;

type SummaryAction = {
  id: string;
  label: string;
  tone?: string;
};

type DetailModalState = {
  title: string;
  fields: SelectableItemDetailField[];
};

function blockStageActionList(block: SummaryCardBlock) {
  const actions = block.metadata?.actions;
  return Array.isArray(actions)
    ? actions.filter(
        (item): item is SummaryAction =>
          typeof item === 'object' &&
          item !== null &&
          'id' in item &&
          'label' in item &&
          typeof item.id === 'string' &&
          typeof item.label === 'string',
      )
    : [];
}

function listInteractionMode(block: SelectableListBlock) {
  return block.metadata?.interactionMode === 'OPEN_DETAIL_MODAL' ? 'OPEN_DETAIL_MODAL' : 'SUBMIT_SELECTION';
}

function selectableItemDetail(item: SelectableItem): DetailModalState | null {
  const detailFields = item.metadata?.detailFields;
  if (!Array.isArray(detailFields)) {
    return null;
  }

  const fields = detailFields.filter(
    (field): field is SelectableItemDetailField =>
      typeof field === 'object' &&
      field !== null &&
      'label' in field &&
      'value' in field &&
      typeof field.label === 'string' &&
      typeof field.value === 'string',
  );

  if (fields.length === 0) {
    return null;
  }

  const title = typeof item.metadata?.detailTitle === 'string' && item.metadata.detailTitle
    ? item.metadata.detailTitle
    : item.label;

  return {
    title,
    fields,
  };
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

function DetailModal({
  title,
  fields,
  onClose,
}: {
  title: string;
  fields: SelectableItemDetailField[];
  onClose: () => void;
}) {
  useEffect(() => {
    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }

    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('keydown', handleEscape);
    };
  }, [onClose]);

  return (
    <div className="brand-dialog-backdrop" onClick={onClose}>
      <div className="brand-dialog-panel" onClick={(event) => event.stopPropagation()}>
        <div className="border-b border-brand-line px-6 pb-4 pt-5">
          <div className="pr-10">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">Registered Payee</p>
            <h3 className="mt-2 text-lg font-semibold text-brand-black">{title}</h3>
          </div>
          <button
            className="absolute right-5 top-5 flex h-9 w-9 items-center justify-center border border-brand-line bg-white text-brand-black transition hover:border-brand-red hover:text-brand-red"
            onClick={onClose}
            aria-label="Close payee detail dialog"
          >
            <CloseIcon className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-3 px-6 pb-6 pt-4">
          {fields.map((field) => (
            <div key={`${field.label}-${field.value}`} className="grid gap-1 border-b border-brand-line pb-3 md:grid-cols-[140px_1fr]">
              <p className="text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">{field.label}</p>
              <p className="text-sm font-medium text-brand-black">{field.value}</p>
            </div>
          ))}
        </div>
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
  const [activeDetail, setActiveDetail] = useState<DetailModalState | null>(null);
  const interactionMode = listInteractionMode(block);

  return (
    <>
      <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
          <span className="text-xs uppercase tracking-[0.14em] text-brand-gray">
            {interactionMode === 'OPEN_DETAIL_MODAL' ? 'DETAILS' : block.selectionMode}
          </span>
        </div>
        <div className="space-y-3">
          {block.items.map((item) => (
            <button
              key={item.itemId}
              className={cn(
                'group w-full border border-brand-line bg-white px-4 py-4 text-left transition',
                disabled ? 'cursor-not-allowed opacity-60' : 'hover:border-brand-red hover:bg-[#fff4f5]',
              )}
              onClick={() => {
                if (interactionMode === 'OPEN_DETAIL_MODAL') {
                  const detail = selectableItemDetail(item);
                  if (detail) {
                    setActiveDetail(detail);
                  }
                  return;
                }

                onSubmit({
                  eventType: 'SELECT_ITEM',
                  sourceMessageId: messageId,
                  sourceBlockId: block.blockId,
                  selectedItemIds: [item.itemId],
                });
              }}
              disabled={disabled}
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-sm font-semibold text-brand-black">{item.label}</p>
                  {item.description ? (
                    <p className="mt-1 text-sm leading-6 text-brand-gray">{item.description}</p>
                  ) : null}
                </div>
                <span
                  className={cn(
                    'mt-1 border px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.14em] transition',
                    interactionMode === 'OPEN_DETAIL_MODAL'
                      ? 'border-brand-line text-brand-gray group-hover:border-brand-red group-hover:text-brand-red'
                      : 'h-5 w-5 border-brand-black group-hover:border-brand-red',
                  )}
                >
                  {interactionMode === 'OPEN_DETAIL_MODAL' ? 'View' : ''}
                </span>
              </div>
            </button>
          ))}
        </div>
      </div>

      {activeDetail ? <DetailModal title={activeDetail.title} fields={activeDetail.fields} onClose={() => setActiveDetail(null)} /> : null}
    </>
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
    <div
      className={cn(
        'border bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] px-5 py-4',
        accent === 'red' ? 'border-red-700' : 'border-brand-line',
      )}
    >
      {title ? <p className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">{title}</p> : null}
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
