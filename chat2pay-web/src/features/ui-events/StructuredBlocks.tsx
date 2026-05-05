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

type EditableField = {
  label: string;
  fieldId: string;
  fieldType: 'DATE';
  value?: string | null;
  minDate?: string | null;
  submitOnChange: boolean;
};

function blockEditableFields(block: SummaryCardBlock): EditableField[] {
  const raw = block.metadata?.editableFields;
  if (!Array.isArray(raw)) return [];
  const result: EditableField[] = [];
  for (const item of raw) {
    if (!isRecord(item)) continue;
    const label = cleanText(item.label);
    const fieldId = cleanText(item.fieldId);
    const fieldType = cleanText(item.fieldType);
    if (!label || !fieldId || fieldType !== 'DATE') continue;
    result.push({
      label,
      fieldId,
      fieldType: 'DATE',
      value: cleanText(item.value),
      minDate: cleanText(item.minDate),
      submitOnChange: item.submitOnChange === true,
    });
  }
  return result;
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
  const directory = parsePayeeDirectory(block.metadata);
  if (directory.length) {
    return <PayeeDirectory title={block.title} payees={directory} mode="view" />;
  }

  // Legacy flat fallback for older payloads.
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

// ---------------------------------------------------------------------------
// Hierarchical payee directory (one payee with N accounts) with pagination at
// both levels. Used both for the read-only registered-payee-results listing and
// for the payee-account-selection card in the AWAITING_PAYEE_SELECTION state.
// ---------------------------------------------------------------------------

type PayeeAccountNode = {
  addressId: string;
  bankCode: string | null;
  bankName: string | null;
  accountNumber: string | null;
  accountProductType: string | null;
  payeeAccountLabel: string | null;
  accountLimit: string | null;
  accountLimitCurrency: string | null;
  remittanceCurrencyCode: string | null;
  effectiveRemittanceCurrency: string | null;
  selectable: boolean;
};

type PayeeNode = {
  contactId: string | null;
  nickName: string | null;
  contactFullName: string | null;
  accounts: PayeeAccountNode[];
};

type DebitAccountCardData = {
  accountId: string;
  accountNumber: string | null;
  productCategoryCode: string | null;
  displayLabel: string | null;
  currency: string | null;
};

function parsePayeeDirectory(metadata: Record<string, unknown> | null | undefined): PayeeNode[] {
  const raw = metadata?.payees;
  if (!Array.isArray(raw)) return [];
  const result: PayeeNode[] = [];
  for (const item of raw) {
    if (!isRecord(item)) continue;
    const accountsRaw = item.accounts;
    if (!Array.isArray(accountsRaw)) continue;
    const accounts: PayeeAccountNode[] = [];
    for (const a of accountsRaw) {
      if (!isRecord(a)) continue;
      const addressId = cleanText(a.addressId);
      if (!addressId) continue;
      accounts.push({
        addressId,
        bankCode: cleanText(a.bankCode),
        bankName: cleanText(a.bankName),
        accountNumber: cleanText(a.accountNumber),
        accountProductType: cleanText(a.accountProductType),
        payeeAccountLabel: cleanText(a.payeeAccountLabel),
        accountLimit: cleanText(a.accountLimit),
        accountLimitCurrency: cleanText(a.accountLimitCurrency),
        remittanceCurrencyCode: cleanText(a.remittanceCurrencyCode),
        effectiveRemittanceCurrency: cleanText(a.effectiveRemittanceCurrency),
        selectable: a.selectable !== false,
      });
    }
    if (accounts.length === 0) continue;
    result.push({
      contactId: cleanText(item.contactId),
      nickName: cleanText(item.nickName),
      contactFullName: cleanText(item.contactFullName),
      accounts,
    });
  }
  return result;
}

function payeeMetadataPageSize(metadata: Record<string, unknown> | null | undefined,
                               key: string, fallback: number): number {
  const raw = metadata?.[key];
  if (typeof raw === 'number' && raw > 0 && Number.isFinite(raw)) return Math.floor(raw);
  return fallback;
}

function parseDebitAccounts(metadata: Record<string, unknown> | null | undefined): DebitAccountCardData[] {
  const raw = metadata?.accounts;
  if (!Array.isArray(raw)) return [];
  const result: DebitAccountCardData[] = [];
  for (const item of raw) {
    if (!isRecord(item)) continue;
    const accountId = cleanText(item.accountId);
    if (!accountId) continue;
    result.push({
      accountId,
      accountNumber: cleanText(item.accountNumber),
      productCategoryCode: cleanText(item.productCategoryCode),
      displayLabel: cleanText(item.displayLabel),
      currency: cleanText(item.currency),
    });
  }
  return result;
}

function debitAccountFromSelectableItem(item: SelectableListBlock['items'][number]): DebitAccountCardData | null {
  const metadata = isRecord(item.metadata) ? item.metadata : null;
  const accountId = cleanText(item.itemId) ?? cleanText(metadata?.accountId);
  if (!accountId) return null;
  return {
    accountId,
    accountNumber: cleanText(metadata?.accountNumber),
    productCategoryCode: cleanText(metadata?.productCategoryCode),
    displayLabel: cleanText(metadata?.displayLabel) ?? cleanText(item.label),
    currency: cleanText(metadata?.currency),
  };
}

function isDebitAccountResultsBlock(block: SummaryCardBlock) {
  return block.metadata?.purpose === 'debit-account-results';
}

function PayeeDirectory({
  title,
  payees,
  mode,
  payeePageSize = 10,
  accountPageSize = 10,
  onSelectAccount,
  disabled = false,
}: {
  title: string;
  payees: PayeeNode[];
  mode: 'view' | 'select';
  payeePageSize?: number;
  accountPageSize?: number;
  onSelectAccount?: (addressId: string) => void;
  disabled?: boolean;
}) {
  const [payeePage, setPayeePage] = useState(0);
  const [expandedKey, setExpandedKey] = useState<string | null>(payees.length === 1 ? payeeKey(payees[0], 0) : null);

  const totalAccounts = payees.reduce((acc, p) => acc + p.accounts.length, 0);
  const totalPayeePages = Math.max(1, Math.ceil(payees.length / payeePageSize));
  const safePage = Math.min(payeePage, totalPayeePages - 1);
  const start = safePage * payeePageSize;
  const visible = payees.slice(start, start + payeePageSize);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-brand-line pb-3">
        <h4 className="text-base font-semibold text-brand-black">{title}</h4>
        <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
          {payees.length} {payees.length === 1 ? 'payee' : 'payees'} · {totalAccounts}{' '}
          {totalAccounts === 1 ? 'account' : 'accounts'}
        </span>
      </div>

      <div className="space-y-2">
        {visible.map((payee, index) => {
          const key = payeeKey(payee, start + index);
          const expanded = expandedKey === key;
          const selectableCount = payee.accounts.filter((a) => a.selectable).length;
          return (
            <PayeeRow
              key={key}
              payee={payee}
              expanded={expanded}
              onToggle={() => setExpandedKey(expanded ? null : key)}
              mode={mode}
              accountPageSize={accountPageSize}
              onSelectAccount={onSelectAccount}
              disabled={disabled}
              selectableCount={selectableCount}
            />
          );
        })}
      </div>

      {totalPayeePages > 1 ? (
        <Pagination
          page={safePage}
          totalPages={totalPayeePages}
          onPageChange={setPayeePage}
          itemLabel="payees"
          totalItems={payees.length}
          pageSize={payeePageSize}
        />
      ) : null}
    </div>
  );
}

function payeeKey(payee: PayeeNode, index: number): string {
  return payee.contactId
    ?? `${payee.nickName ?? ''}|${payee.contactFullName ?? ''}|${index}`;
}

function PayeeRow({
  payee,
  expanded,
  onToggle,
  mode,
  accountPageSize,
  onSelectAccount,
  disabled,
  selectableCount,
}: {
  payee: PayeeNode;
  expanded: boolean;
  onToggle: () => void;
  mode: 'view' | 'select';
  accountPageSize: number;
  onSelectAccount?: (addressId: string) => void;
  disabled: boolean;
  selectableCount: number;
}) {
  const showSelectable = mode === 'select';
  const noSelectable = showSelectable && selectableCount === 0;
  return (
    <div className="border border-brand-line bg-white shadow-[0_10px_24px_rgba(17,17,17,0.06)]">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-start justify-between gap-3 p-4 text-left transition hover:bg-[#fff8f8]"
      >
        <div className="min-w-0">
          <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
            {payee.nickName ? 'Nickname' : 'Payee'}
          </p>
          <h4 className="mt-1 break-words text-base font-semibold leading-6 text-brand-black">
            {payee.nickName ?? payee.contactFullName ?? 'Unnamed payee'}
          </h4>
          {payee.contactFullName && payee.contactFullName !== payee.nickName ? (
            <p className="mt-1 break-words text-sm text-brand-gray">{payee.contactFullName}</p>
          ) : null}
        </div>
        <div className="flex flex-col items-end gap-2">
          <span className="border border-brand-line bg-[#fafafa] px-2 py-0.5 text-[11px] font-semibold uppercase tracking-[0.12em] text-brand-gray">
            {payee.accounts.length} {payee.accounts.length === 1 ? 'account' : 'accounts'}
          </span>
          <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
            {expanded ? 'Hide' : 'Show'}
          </span>
        </div>
      </button>

      {expanded ? (
        noSelectable ? (
          <div className="border-t border-brand-line bg-[#fff4f5] px-4 py-3 text-sm text-brand-red">
            No domestic-payable accounts. Cross-border payment is not supported in this POC.
          </div>
        ) : (
          <PayeeAccountList
            accounts={payee.accounts}
            mode={mode}
            pageSize={accountPageSize}
            onSelect={onSelectAccount}
            disabled={disabled}
          />
        )
      ) : null}
    </div>
  );
}

function PayeeAccountList({
  accounts,
  mode,
  pageSize,
  onSelect,
  disabled,
}: {
  accounts: PayeeAccountNode[];
  mode: 'view' | 'select';
  pageSize: number;
  onSelect?: (addressId: string) => void;
  disabled: boolean;
}) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(accounts.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const start = safePage * pageSize;
  const visible = accounts.slice(start, start + pageSize);
  const showSelect = mode === 'select';

  return (
    <div className="border-t border-brand-line bg-[#fcfcfc]">
      <div className="grid gap-2 p-4">
        {visible.map((account) => (
          <PayeeAccountCard
            key={account.addressId}
            account={account}
            mode={mode}
            disabled={disabled}
            onSelect={showSelect ? onSelect : undefined}
          />
        ))}
      </div>
      {totalPages > 1 ? (
        <div className="border-t border-brand-line px-4 pb-4">
          <Pagination
            page={safePage}
            totalPages={totalPages}
            onPageChange={setPage}
            itemLabel="accounts"
            totalItems={accounts.length}
            pageSize={pageSize}
          />
        </div>
      ) : null}
    </div>
  );
}

function PayeeAccountCard({
  account,
  mode,
  disabled,
  onSelect,
}: {
  account: PayeeAccountNode;
  mode: 'view' | 'select';
  disabled: boolean;
  onSelect?: (addressId: string) => void;
}) {
  const limitDisplay =
    account.accountLimit && account.accountLimitCurrency
      ? `${account.accountLimitCurrency} ${formatNumber(account.accountLimit)}`
      : account.accountLimit ?? null;
  const currency = account.effectiveRemittanceCurrency ?? account.accountLimitCurrency;
  const isInternational = account.payeeAccountLabel?.toLowerCase() === 'international';
  const tooltip = isInternational
    ? 'Cross-border payment is not supported in this POC.'
    : undefined;

  const card = (
    <div className="grid gap-3 p-3 sm:grid-cols-[1fr_auto] sm:items-start">
      <div className="grid gap-1">
        <p className="break-all text-sm font-semibold text-brand-black">
          {account.accountNumber ?? account.addressId}
        </p>
        <p className="text-xs text-brand-gray">
          {account.accountProductType ?? '—'}
          {account.bankName ? ` • ${account.bankName}` : ''}
          {account.bankCode ? ` (${account.bankCode})` : ''}
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px]">
          <PayeeLabelBadge label={account.payeeAccountLabel} />
          {currency ? (
            <span className="border border-brand-line bg-white px-2 py-0.5 font-semibold uppercase tracking-[0.12em] text-brand-gray">
              {currency}
            </span>
          ) : null}
          {limitDisplay ? (
            <span className="text-[11px] text-brand-gray">Limit {limitDisplay}</span>
          ) : null}
        </div>
      </div>
      {mode === 'select' ? (
        <BrandButton
          variant={account.selectable ? 'primary' : 'secondary'}
          disabled={disabled || !account.selectable}
          onClick={() => onSelect?.(account.addressId)}
        >
          {account.selectable ? 'Choose' : 'Unavailable'}
        </BrandButton>
      ) : null}
    </div>
  );

  return (
    <div
      className={cn(
        'border border-brand-line bg-white',
        !account.selectable && mode === 'select' ? 'opacity-70' : null,
      )}
      title={tooltip}
    >
      {card}
    </div>
  );
}

function PayeeLabelBadge({ label }: { label: string | null }) {
  if (!label) return null;
  const isInternational = label.toLowerCase() === 'international';
  return (
    <span
      className={cn(
        'border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.12em]',
        isInternational
          ? 'border-[#e8a7ad] bg-[#fff4f5] text-brand-red'
          : 'border-[#8ecfca] bg-[#e4f3f1] text-[#00847f]',
      )}
    >
      {label}
    </span>
  );
}

function DebitAccountCardContent({ account }: { account: DebitAccountCardData }) {
  return (
    <div className="grid gap-3">
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
          Debit account
        </p>
        <h4 className="mt-1 break-words text-base font-semibold leading-6 text-brand-black">
          {account.displayLabel ?? account.accountNumber ?? 'Unnamed account'}
        </h4>
      </div>
      <div className="grid gap-2">
        <PayeeDetail label="Number" value={account.accountNumber} />
        <PayeeDetail label="Product" value={account.productCategoryCode} />
        <PayeeDetail label="Currency" value={account.currency} />
      </div>
    </div>
  );
}

function DebitAccountList({
  title,
  accounts,
  mode,
  pageSize = 10,
  disabled = false,
  onSelectAccount,
}: {
  title: string;
  accounts: DebitAccountCardData[];
  mode: 'view' | 'select';
  pageSize?: number;
  disabled?: boolean;
  onSelectAccount?: (accountId: string) => void;
}) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(accounts.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const start = safePage * pageSize;
  const visible = accounts.slice(start, start + pageSize);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3 border-b border-brand-line pb-3">
        <h4 className="text-base font-semibold text-brand-black">{title}</h4>
        <span className="text-[11px] font-semibold uppercase tracking-[0.14em] text-brand-gray">
          {accounts.length} {accounts.length === 1 ? 'account' : 'accounts'}
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {visible.map((account) => (
          <article
            key={account.accountId}
            className="border border-brand-line bg-white p-4 shadow-[0_10px_24px_rgba(17,17,17,0.06)]"
          >
            <DebitAccountCardContent account={account} />
            {mode === 'select' ? (
              <div className="mt-4 flex justify-end border-t border-brand-line pt-3">
                <BrandButton disabled={disabled} onClick={() => onSelectAccount?.(account.accountId)}>
                  Choose
                </BrandButton>
              </div>
            ) : null}
          </article>
        ))}
      </div>
      {totalPages > 1 ? (
        <Pagination
          page={safePage}
          totalPages={totalPages}
          onPageChange={setPage}
          itemLabel="accounts"
          totalItems={accounts.length}
          pageSize={pageSize}
        />
      ) : null}
    </div>
  );
}

function Pagination({
  page,
  totalPages,
  onPageChange,
  itemLabel,
  totalItems,
  pageSize,
}: {
  page: number;
  totalPages: number;
  onPageChange: (next: number) => void;
  itemLabel: string;
  totalItems: number;
  pageSize: number;
}) {
  const start = page * pageSize + 1;
  const end = Math.min(totalItems, (page + 1) * pageSize);
  return (
    <div className="flex items-center justify-between gap-3 pt-3">
      <span className="text-[11px] text-brand-gray">
        Showing {start}–{end} of {totalItems} {itemLabel}
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="border border-brand-line bg-white px-3 py-1 text-xs font-semibold text-brand-black disabled:opacity-50"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          Prev
        </button>
        <span className="text-[11px] text-brand-gray">
          {page + 1} / {totalPages}
        </span>
        <button
          type="button"
          className="border border-brand-line bg-white px-3 py-1 text-xs font-semibold text-brand-black disabled:opacity-50"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}

function formatNumber(raw: string | null): string {
  if (raw == null) return '';
  const n = Number(raw);
  if (!Number.isFinite(n)) return raw;
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
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
  const isPayeeAccountSelection = block.metadata?.purpose === 'payee-account-selection';
  const isDebitAccountSelection = block.metadata?.purpose === 'debit-account-selection';

  if (isPayeeAccountSelection) {
    const directory = parsePayeeDirectory(block.metadata);
    if (directory.length) {
      return (
        <PayeeDirectory
          title={block.title}
          payees={directory}
          mode="select"
          payeePageSize={payeeMetadataPageSize(block.metadata, 'payeePageSize', 10)}
          accountPageSize={payeeMetadataPageSize(block.metadata, 'accountPageSize', 10)}
          disabled={disabled}
          onSelectAccount={(addressId) =>
            onSubmit({
              eventType: 'SELECT_ITEM',
              sourceMessageId: messageId,
              sourceBlockId: block.blockId,
              selectedItemId: addressId,
            })
          }
        />
      );
    }
  }

  if (isDebitAccountSelection) {
    const accountsFromMetadata = parseDebitAccounts(block.metadata);
    const accounts = accountsFromMetadata.length
      ? accountsFromMetadata
      : block.items
          .map(debitAccountFromSelectableItem)
          .filter((item): item is DebitAccountCardData => item !== null);
    if (accounts.length) {
      return (
        <DebitAccountList
          title={block.title}
          accounts={accounts}
          mode="select"
          pageSize={payeeMetadataPageSize(block.metadata, 'pageSize', 10)}
          disabled={disabled}
          onSelectAccount={(accountId) =>
            onSubmit({
              eventType: 'SELECT_ITEM',
              sourceMessageId: messageId,
              sourceBlockId: block.blockId,
              selectedItemId: accountId,
            })
          }
        />
      );
    }
  }

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
  const editableFields = blockEditableFields(block);
  const editableByLabel = new Map(editableFields.map((field) => [field.label, field]));
  const [editValues, setEditValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(editableFields.map((field) => [field.fieldId, field.value ?? ''])),
  );

  if (isPayeeResultsBlock(block)) {
    return <PayeeResultsCardList block={block} />;
  }
  if (isDebitAccountResultsBlock(block)) {
    const accounts = parseDebitAccounts(block.metadata);
    if (accounts.length) {
      return (
        <DebitAccountList
          title={block.title}
          accounts={accounts}
          mode="view"
          pageSize={payeeMetadataPageSize(block.metadata, 'pageSize', 10)}
        />
      );
    }
  }

  const formValuesForActions = (action: { id: string }) =>
    action.id === 'CONFIRM_PAYMENT' && Object.keys(editValues).length
      ? editValues
      : undefined;

  const handleEditableChange = (editable: EditableField, nextValue: string) => {
    setEditValues((current) => ({ ...current, [editable.fieldId]: nextValue }));
    if (editable.submitOnChange && nextValue) {
      onSubmit({
        eventType: 'SUBMIT_FORM',
        sourceMessageId: messageId,
        sourceBlockId: block.blockId,
        formValues: { [editable.fieldId]: nextValue },
      });
    }
  };

  return (
    <div className="brand-panel bg-[linear-gradient(180deg,#ffffff_0%,#fcfcfc_100%)] p-5">
      <div className="mb-5 flex items-center justify-between gap-4">
        <h4 className="text-base font-semibold text-brand-black">{block.title}</h4>
        <div className="h-1 w-16 bg-brand-red" />
      </div>
      <div className="grid gap-3">
        {block.fields.map((field) => {
          const pending = isPendingValue(field.value);
          const editable = editableByLabel.get(field.label) ?? null;
          return (
            <div
              key={`${field.label}-${field.value}`}
              className={cn(
                'grid gap-1 border-b border-brand-line py-3 md:grid-cols-[160px_1fr]',
                pending && !editable && 'border-l-4 border-l-brand-red bg-[#fff4f5] px-3',
              )}
            >
              <div className="flex items-center gap-2">
                <p className="text-xs font-semibold uppercase tracking-[0.14em] text-brand-gray">{field.label}</p>
                {pending && !editable ? (
                  <span className="border border-[#e8a7ad] bg-white px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-brand-red">
                    Needed
                  </span>
                ) : null}
              </div>
              {editable ? (
                <input
                  type="date"
                  className="brand-input w-fit min-w-[160px]"
                  value={editValues[editable.fieldId] ?? ''}
                  min={editable.minDate ?? undefined}
                  disabled={disabled}
                  onChange={(event) => handleEditableChange(editable, event.target.value)}
                />
              ) : (
                <p className={cn('break-words text-sm font-medium', pending ? 'text-brand-red' : 'text-brand-black')}>
                  {field.value}
                </p>
              )}
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
              onClick={() =>
                onSubmit({
                  eventType: 'CLICK_ACTION',
                  sourceMessageId: messageId,
                  sourceBlockId: block.blockId,
                  actionValue: action.id,
                  formValues: formValuesForActions(action),
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
        'mt-[3px] inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[11px] font-bold leading-none text-white',
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
