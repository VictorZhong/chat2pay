import type {
  CapabilityType,
  ChatMessage,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatTurnResponse,
  ContentBlock,
  CurrentUserContext,
  DisplayField,
  PaymentDraft,
  PayeeSummary,
  ProfileLoginRequest,
  ProfileSummary,
  SelectableItem,
  SendMessageRequest,
  SummaryCardBlock,
  UiEventRequest,
} from '@/shared/api/contracts';
import { MOCK_API_LATENCY_MS } from '@/shared/config/env';
import { createId } from '@/shared/lib/id';

type RegisteredPayee = PayeeSummary & {
  aliases: string[];
  // Mock fixtures group accounts under a contact via these fields. Production payloads come
  // from the bank API in a hierarchical shape; here we keep the flat list for compatibility
  // with the rest of the mock server but emit the directory metadata derived from it.
  contactId: string;
  contactFullName: string;
  accountProductType: string;
  payeeAccountLabel: 'Local' | 'International';
  accountLimit: string;
  accountLimitCurrency: string;
  remittanceCurrencyCode?: string;
};

type MockDebitAccount = {
  accountId: string;
  parentAccountId: string;
  accountDisplay: string;
  productCategoryCode: string;
  productDescription: string;
  displayLabel: string;
  currency?: string | null;
  ledgerBalanceIndicator: string;
  ledgerBalanceAmount?: string | null;
  ledgerBalanceCurrency?: string | null;
};

type MockDebitAccountGroup = {
  groupId: string;
  parentAccountId: string;
  accountDisplay: string;
  productDescription: string;
  subAccounts: MockDebitAccount[];
};

type MockSessionRecord = {
  profileId: string;
  session: ChatSessionDetail;
  messages: ChatMessage[];
};

type MockDatabase = {
  profiles: ProfileSummary[];
  sessionsByProfile: Record<string, MockSessionRecord[]>;
};

const STORAGE_KEY = 'chat2pay-mock-db-v2';
const POC_ACCESS_PASSWORD = 'tb123';
const DEFAULT_CAPABILITIES: CapabilityType[] = ['REGISTERED_PAYEE_LOOKUP', 'DOMESTIC_PAYMENT'];

const storageFallback = new Map<string, string>();
let memoryDb: MockDatabase | null = null;

const REGISTERED_PAYEES: RegisteredPayee[] = [
  {
    payeeId: 'payee_bob_current',
    name: 'Bob Chan',
    contactId: 'contact_bob',
    contactFullName: 'CHAN TAI MING',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '004',
    bankName: 'HSBC Hong Kong',
    accountNumber: '123-456-789',
    displayLabel: 'CURRENT • 123-456-789',
    accountProductType: 'HSBC HKD Current',
    payeeAccountLabel: 'Local',
    accountLimit: '50000.00',
    accountLimitCurrency: 'HKD',
    remittanceCurrencyCode: 'HKD',
    aliases: ['bob chan', 'bob'],
  },
  {
    payeeId: 'payee_bob_savings',
    name: 'Bob Chan',
    contactId: 'contact_bob',
    contactFullName: 'CHAN TAI MING',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '004',
    bankName: 'HSBC Hong Kong',
    accountNumber: '987-654-321',
    displayLabel: 'SAVINGS • 987-654-321',
    accountProductType: 'HSBC HKD Savings',
    payeeAccountLabel: 'Local',
    accountLimit: '50000.00',
    accountLimitCurrency: 'HKD',
    remittanceCurrencyCode: 'HKD',
    aliases: ['bob chan', 'bob'],
  },
  {
    payeeId: 'payee_bob_intl',
    name: 'Bob Chan',
    contactId: 'contact_bob',
    contactFullName: 'CHAN TAI MING',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '004',
    bankName: 'HSBC Hong Kong',
    accountNumber: '887-001-555',
    displayLabel: 'USD SAVINGS • 887-001-555',
    accountProductType: 'HSBC USD Savings',
    payeeAccountLabel: 'International',
    accountLimit: '20000.00',
    accountLimitCurrency: 'USD',
    remittanceCurrencyCode: 'USD',
    aliases: ['bob chan', 'bob'],
  },
  {
    payeeId: 'payee_sarah_salary',
    name: 'Sarah Wong',
    contactId: 'contact_sarah',
    contactFullName: 'WONG WAI MAN',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '012',
    bankName: 'Bank of China (Hong Kong)',
    accountNumber: '800-221-456',
    displayLabel: 'PAYROLL • 800-221-456',
    accountProductType: 'BOCHK HKD Current',
    payeeAccountLabel: 'Local',
    accountLimit: '100000.00',
    accountLimitCurrency: 'HKD',
    remittanceCurrencyCode: 'HKD',
    aliases: ['sarah wong', 'sarah'],
  },
  {
    payeeId: 'payee_alex_ops',
    name: 'Alex Tan',
    contactId: 'contact_alex',
    contactFullName: 'TAN WEI HONG',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '024',
    bankName: 'Hang Seng Bank',
    accountNumber: '556-000-912',
    displayLabel: 'OPERATIONS • 556-000-912',
    accountProductType: 'HSB HKD Current',
    payeeAccountLabel: 'Local',
    accountLimit: '30000.00',
    accountLimitCurrency: 'HKD',
    remittanceCurrencyCode: 'HKD',
    aliases: ['alex tan', 'alex'],
  },
  {
    payeeId: 'payee_michelle_vendor',
    name: 'Michelle Ng',
    contactId: 'contact_michelle',
    contactFullName: 'NG SIU LING',
    payeeType: 'DOMESTIC_REGISTERED',
    bankCode: '005',
    bankName: 'Citibank Hong Kong',
    accountNumber: '445-221-007',
    displayLabel: 'VENDOR • 445-221-007',
    accountProductType: 'Citi HKD Current',
    payeeAccountLabel: 'Local',
    accountLimit: '15000.00',
    accountLimitCurrency: 'HKD',
    remittanceCurrencyCode: 'HKD',
    aliases: ['michelle ng', 'michelle'],
  },
];

const DEBIT_ACCOUNT_GROUPS: MockDebitAccountGroup[] = [
  {
    groupId: 'acct_master_1',
    parentAccountId: 'acct_master_1',
    accountDisplay: '118-067271-833',
    productDescription: 'zzzz One',
    subAccounts: [
      {
        accountId: 'acct_primary',
        parentAccountId: 'acct_master_1',
        accountDisplay: '118-067271-833',
        productCategoryCode: 'PVCUA',
        productDescription: 'HKD Current',
        displayLabel: 'HKD Current • 118-067271-833',
        currency: 'HKD',
        ledgerBalanceIndicator: 'BALANCE_AVAILABLE',
        ledgerBalanceAmount: '999999999',
        ledgerBalanceCurrency: 'HKD',
      },
      {
        accountId: 'acct_savings',
        parentAccountId: 'acct_master_1',
        accountDisplay: '118-067271-833',
        productCategoryCode: 'PVSAV',
        productDescription: 'HKD Savings',
        displayLabel: 'HKD Savings • 118-067271-833',
        currency: 'HKD',
        ledgerBalanceIndicator: 'BALANCE_AVAILABLE',
        ledgerBalanceAmount: '888888888',
        ledgerBalanceCurrency: 'HKD',
      },
      {
        accountId: 'acct_notice',
        parentAccountId: 'acct_master_1',
        accountDisplay: '118-067271-833',
        productCategoryCode: 'PVNTA',
        productDescription: 'USD Savings',
        displayLabel: 'USD Savings • 118-067271-833',
        currency: null,
        ledgerBalanceIndicator: 'NO_BALANCE',
        ledgerBalanceAmount: null,
        ledgerBalanceCurrency: null,
      },
    ],
  },
  {
    groupId: 'acct_master_2',
    parentAccountId: 'acct_master_2',
    accountDisplay: '128-000991-001',
    productDescription: 'zzzz One',
    subAccounts: [
      {
        accountId: 'acct_aud',
        parentAccountId: 'acct_master_2',
        accountDisplay: '128-000991-001',
        productCategoryCode: 'PVSAV',
        productDescription: 'AUD Savings',
        displayLabel: 'AUD Savings • 128-000991-001',
        currency: 'AUD',
        ledgerBalanceIndicator: 'BALANCE_AVAILABLE',
        ledgerBalanceAmount: '100000000',
        ledgerBalanceCurrency: 'AUD',
      },
    ],
  },
];

function withLatency<T>(factory: () => T) {
  return new Promise<T>((resolve, reject) => {
    window.setTimeout(() => {
      try {
        resolve(factory());
      } catch (error) {
        reject(error);
      }
    }, MOCK_API_LATENCY_MS);
  });
}

function nowIso() {
  return new Date().toISOString();
}

function todayDate() {
  return new Date().toISOString().slice(0, 10);
}

function futureDate(days: number) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}

function readStorage(key: string) {
  if (
    typeof window !== 'undefined' &&
    window.localStorage &&
    typeof window.localStorage.getItem === 'function'
  ) {
    return window.localStorage.getItem(key);
  }

  return storageFallback.get(key) ?? null;
}

function writeStorage(key: string, value: string) {
  if (
    typeof window !== 'undefined' &&
    window.localStorage &&
    typeof window.localStorage.setItem === 'function'
  ) {
    window.localStorage.setItem(key, value);
    return;
  }

  storageFallback.set(key, value);
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function normalize(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
}

function knownMockCurrencyCodes() {
  return new Set(
    [
      ...REGISTERED_PAYEES.flatMap((payee) => [payee.accountLimitCurrency, payee.remittanceCurrencyCode]),
      ...DEBIT_ACCOUNT_GROUPS.flatMap((group) =>
        group.subAccounts.flatMap((account) => [account.currency, account.ledgerBalanceCurrency]),
      ),
    ]
      .filter((value): value is string => typeof value === 'string' && value.trim().length > 0)
      .map((value) => value.toLowerCase()),
  );
}

function titleCaseWords(value: string) {
  const currencyCodes = knownMockCurrencyCodes();
  return value
    .split(' ')
    .filter(Boolean)
    .map((part) => {
      const normalized = part.toLowerCase();
      return currencyCodes.has(normalized)
        ? normalized.toUpperCase()
        : part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
    })
    .join(' ');
}

function buildTextBlock(text: string, title?: string): ContentBlock {
  return {
    blockId: createId('blk_text'),
    type: 'TEXT',
    title,
    text,
  };
}

function buildInfoCard(title: string, text: string): ContentBlock {
  return {
    blockId: createId('blk_info'),
    type: 'INFO_CARD',
    title,
    text,
  };
}

function buildErrorCard(title: string, text: string): ContentBlock {
  return {
    blockId: createId('blk_error'),
    type: 'ERROR_CARD',
    title,
    text,
  };
}

function buildSummaryCard(
  title: string,
  fields: DisplayField[],
  metadata?: Record<string, unknown>,
): SummaryCardBlock {
  return {
    blockId: createId('blk_summary'),
    type: 'SUMMARY_CARD',
    title,
    fields,
    metadata,
  };
}

function buildSelectableList(
  title: string,
  items: SelectableItem[],
  metadata?: Record<string, unknown>,
): ContentBlock {
  return {
    blockId: createId('blk_list'),
    type: 'SELECTABLE_LIST',
    title,
    items,
    metadata,
  };
}

function buildMessage(input: Omit<ChatMessage, 'messageId' | 'createdAt'>): ChatMessage {
  return {
    ...input,
    messageId: createId('msg'),
    createdAt: nowIso(),
  };
}

function buildAssistantMessage(sessionId: string, blocks: ContentBlock[], text?: string): ChatMessage {
  return buildMessage({
    sessionId,
    role: 'ASSISTANT',
    kind: blocks.length ? 'BLOCKS' : 'TEXT',
    text,
    contentBlocks: blocks.length ? blocks : null,
    metadata: null,
  });
}

function buildUserTextMessage(sessionId: string, text: string) {
  return buildMessage({
    sessionId,
    role: 'USER',
    kind: 'TEXT',
    text,
    contentBlocks: null,
    metadata: null,
  });
}

function buildUserEventMessage(sessionId: string, text: string) {
  return buildMessage({
    sessionId,
    role: 'USER',
    kind: 'UI_EVENT',
    text,
    contentBlocks: null,
    metadata: null,
  });
}

function cleanText(value: unknown) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
}

function metadataText(metadata: Record<string, unknown> | null | undefined, key: string) {
  return cleanText(metadata?.[key]);
}

function joinNonBlank(separator: string, ...values: Array<string | null | undefined>) {
  const parts = values.map((value) => cleanText(value)).filter((value): value is string => Boolean(value));
  return parts.length ? parts.join(separator) : null;
}

function sourceBlockFromRecord(record: MockSessionRecord, request: UiEventRequest) {
  const sourceMessage = record.messages.find((message) => message.messageId === request.sourceMessageId);
  return sourceMessage?.contentBlocks?.find((block) => block.blockId === request.sourceBlockId);
}

function actionLabelFromSummaryBlock(block: ContentBlock | undefined, actionId: string) {
  if (!block || block.type !== 'SUMMARY_CARD') {
    return actionId;
  }

  const actions = Array.isArray(block.metadata?.actions) ? block.metadata.actions : [];
  const matched = actions.find(
    (item): item is { id: string; label: string } =>
      typeof item === 'object'
      && item !== null
      && 'id' in item
      && 'label' in item
      && item.id === actionId
      && typeof item.label === 'string',
  );

  return matched?.label ?? actionId;
}

function describeSelectableItem(block: Extract<ContentBlock, { type: 'SELECTABLE_LIST' }>, selectedId: string) {
  const item = block.items.find((candidate) => candidate.itemId === selectedId);
  if (!item) return null;
  const metadata =
    item.metadata && typeof item.metadata === 'object' && !Array.isArray(item.metadata)
      ? item.metadata as Record<string, unknown>
      : null;
  const purpose = metadataText(block.metadata as Record<string, unknown> | undefined, 'purpose');

  if (purpose === 'debit-account-selection') {
    const label = joinNonBlank(
      ' • ',
      metadataText(metadata, 'productDescription'),
      metadataText(metadata, 'accountDisplay'),
    ) ?? cleanText(item.label) ?? cleanText(item.description) ?? selectedId;
    return `Chose debit account ${label}`;
  }

  if (purpose === 'payee-selection' || purpose === 'payee-account-selection') {
    const accountLabel =
      metadataText(metadata, 'displayLabel')
      ?? joinNonBlank(' - ', metadataText(metadata, 'accountProductType'), metadataText(metadata, 'accountNumber'))
      ?? cleanText(item.description);
    const label = joinNonBlank(
      ' • ',
      metadataText(metadata, 'payeeNickName') ?? cleanText(item.label),
      accountLabel,
    ) ?? cleanText(item.label) ?? selectedId;
    return `Chose payee ${label}`;
  }

  return `Chose ${cleanText(item.label) ?? selectedId}`;
}

function describeFormEvent(request: UiEventRequest) {
  const formValues = request.formValues ?? {};
  const payee = cleanText(formValues.payee);
  const amount = cleanText(formValues.amount);
  const paymentDate = cleanText(formValues.paymentDate);

  if (Object.keys(formValues).length === 1 && paymentDate) return `Chose payment date ${paymentDate}`;
  if (Object.keys(formValues).length === 1 && amount) return `Entered amount ${amount}`;
  if (Object.keys(formValues).length === 1 && payee) return `Entered payee ${payee}`;

  const parts = [
    payee ? `payee ${payee}` : null,
    amount ? `amount ${amount}` : null,
    paymentDate ? `payment date ${paymentDate}` : null,
  ].filter((value): value is string => Boolean(value));
  return parts.length ? `Submitted details: ${parts.join(', ')}` : 'Submitted details';
}

function describeUiEvent(record: MockSessionRecord, request: UiEventRequest) {
  if (request.eventType === 'SUBMIT_FORM') {
    return describeFormEvent(request);
  }

  const sourceBlock = sourceBlockFromRecord(record, request);
  const selectedId = request.selectedItemId ?? request.actionValue;

  if (!selectedId) {
    return 'Submitted action';
  }

  if (request.eventType === 'CLICK_ACTION') {
    return actionLabelFromSummaryBlock(sourceBlock, selectedId);
  }

  if (sourceBlock?.type === 'SELECTABLE_LIST') {
    return describeSelectableItem(sourceBlock, selectedId) ?? 'Chose item';
  }

  return 'Submitted action';
}

function previewFromMessage(message: ChatMessage) {
  const direct = message.text?.trim();
  if (direct) {
    return direct.slice(0, 140);
  }

  const firstBlock = message.contentBlocks?.[0];
  if (!firstBlock) {
    return '';
  }

  if ('text' in firstBlock && typeof firstBlock.text === 'string') {
    return firstBlock.text.slice(0, 140);
  }

  if ('title' in firstBlock && typeof firstBlock.title === 'string') {
    return firstBlock.title.slice(0, 140);
  }

  return '';
}

function appendMessage(record: MockSessionRecord, message: ChatMessage) {
  record.messages.push(message);
  record.session.updatedAt = message.createdAt;
  record.session.lastMessagePreview = previewFromMessage(message);
}

function touchSession(record: MockSessionRecord, state: ChatSessionDetail['state']) {
  record.session.state = state;
  record.session.updatedAt = nowIso();
}

function setSessionStatus(record: MockSessionRecord, status: ChatSessionDetail['status']) {
  record.session.status = status;
  record.session.updatedAt = nowIso();
}

function ensureDomesticDraft(record: MockSessionRecord) {
  if (record.session.activeDraft) {
    return record.session.activeDraft;
  }

  const draft: PaymentDraft = {
    draftId: createId('draft'),
    sessionId: record.session.sessionId,
    paymentType: 'DOMESTIC_PAYMENT',
    status: 'DRAFT',
    payeeQueryText: null,
    selectedPayee: null,
    selectedDebitAccount: null,
    amount: null,
    currency: 'HKD',
    paymentDate: null,
    downstreamReference: null,
    lastError: null,
    context: null,
    lastUpdatedAt: nowIso(),
  };
  record.session.activeDraft = draft;
  return draft;
}

function dropDraft(record: MockSessionRecord) {
  record.session.activeDraft = null;
}

function updateDraftTimestamp(draft: PaymentDraft) {
  draft.lastUpdatedAt = nowIso();
}

function draftSummaryFields(draft: PaymentDraft): DisplayField[] {
  return [
    { label: 'Payee', value: draft.selectedPayee?.name ?? draft.payeeQueryText ?? 'Pending' },
    { label: 'Account', value: draft.selectedPayee?.displayLabel ?? 'Pending' },
    { label: 'Bank', value: draft.selectedPayee?.bankName ?? 'Pending' },
    {
      label: 'Debit account',
      value:
        draft.selectedDebitAccount?.displayLabel
        ?? draft.selectedDebitAccount?.productDescription
        ?? draft.selectedDebitAccount?.accountDisplay
        ?? 'Pending',
    },
    {
      label: 'Amount',
      value:
        draft.amount !== null && draft.amount !== undefined
          ? new Intl.NumberFormat('en-HK', {
              style: 'currency',
              currency: draft.currency ?? 'HKD',
              minimumFractionDigits: 2,
            }).format(draft.amount)
          : 'Pending',
    },
    { label: 'Payment date', value: draft.paymentDate ?? 'Pending' },
  ];
}

function buildWelcomeBlocks(): ContentBlock[] {
  return [
    buildTextBlock(
      'Ask about a registered payee, or tell me who to pay, how much, and whether it should go today or tomorrow.',
      'Domestic payments only',
    ),
  ];
}

function buildMissingDetailsBlocks(draft: PaymentDraft): ContentBlock[] {
  const missing: string[] = [];

  if (!draft.payeeQueryText && !draft.selectedPayee) {
    missing.push('payee');
  }

  if (draft.amount === null || draft.amount === undefined) {
    missing.push('amount');
  }

  if (!draft.paymentDate) {
    missing.push('payment date');
  }

  const prompt =
    missing.length === 1
      ? `I still need the ${missing[0]} before I can prepare the domestic payment.`
      : `I still need these details before I can prepare the domestic payment: ${missing.join(', ')}.`;
  const withAccountHint =
    draft.selectedPayee && !draft.selectedDebitAccount
      ? `${prompt} Once those are set, I will show the eligible debit accounts for you to choose from.`
      : prompt;

  return [
    buildTextBlock(withAccountHint, 'Need more details'),
    buildSummaryCard('Current draft', draftSummaryFields(draft), {
      editableFields: [editablePaymentDateField(draft, true)],
    }),
  ];
}

function editablePaymentDateField(draft: PaymentDraft, submitOnChange: boolean) {
  const today = new Date().toISOString().slice(0, 10);
  return {
    label: 'Payment date',
    fieldId: 'paymentDate',
    fieldType: 'DATE',
    value: draft.paymentDate ?? '',
    minDate: today,
    submitOnChange,
  };
}

function buildPayeeSelectionBlocks(query: string, matches: RegisteredPayee[]): ContentBlock[] {
  const contacts = groupPayeesByContact(matches);
  const headline =
    contacts.length === 1
      ? `I found one registered payee for "${query}" with multiple accounts. Please choose the account to pay.`
      : `I found ${contacts.length} registered payees for "${query}". Please choose the payee and account.`;
  return [
    buildTextBlock(headline, 'Choose payee'),
    buildSelectableList(
      'Registered payee matches',
      matches.map((account) => ({
        itemId: account.payeeId,
        label: account.name,
        description: `${account.bankName} • ${account.displayLabel}`,
        metadata: payeeAccountMetadata(account),
      })),
      {
        purpose: 'payee-account-selection',
        payeeCount: contacts.length,
        payees: contacts.map(payeeContactMetadata),
        payeePageSize: 10,
        accountPageSize: 10,
      },
    ),
  ];
}

function debitAccountMetadata(account: MockDebitAccount) {
  return {
    accountId: account.accountId,
    parentAccountId: account.parentAccountId,
    accountDisplay: account.accountDisplay,
    productCategoryCode: account.productCategoryCode,
    productDescription: account.productDescription,
    displayLabel: account.displayLabel,
    currency: account.currency,
    ledgerBalanceIndicator: account.ledgerBalanceIndicator,
    ledgerBalanceAmount: account.ledgerBalanceAmount,
    ledgerBalanceCurrency: account.ledgerBalanceCurrency,
  };
}

function debitAccountGroupMetadata(group: MockDebitAccountGroup) {
  return {
    groupId: group.groupId,
    parentAccountId: group.parentAccountId,
    accountDisplay: group.accountDisplay,
    productDescription: group.productDescription,
    subAccountCount: group.subAccounts.length,
    subAccounts: group.subAccounts.map(debitAccountMetadata),
  };
}

function allDebitAccounts() {
  return DEBIT_ACCOUNT_GROUPS.flatMap((group) => group.subAccounts);
}

function buildDebitAccountSelectionBlocks(groups: MockDebitAccountGroup[]): ContentBlock[] {
  const accounts = groups.flatMap((group) => group.subAccounts);
  return [
    buildTextBlock(
      'Please choose the debit account to fund this domestic payment.',
      'Choose debit account',
    ),
    buildSelectableList(
      'Available debit accounts',
      accounts.map((account) => ({
        itemId: account.accountId,
        label: account.displayLabel,
        description:
          account.ledgerBalanceIndicator === 'BALANCE_AVAILABLE'
            ? `${account.productDescription} • ${account.ledgerBalanceCurrency ?? account.currency} ${account.ledgerBalanceAmount ?? ''}`.trim()
            : `${account.productDescription} • ${account.ledgerBalanceIndicator}`,
        metadata: debitAccountMetadata(account),
      })),
      {
        purpose: 'debit-account-selection',
        groupCount: groups.length,
        accountCount: accounts.length,
        groupPageSize: 10,
        subAccountPageSize: 10,
        groups: groups.map(debitAccountGroupMetadata),
      },
    ),
  ];
}

function buildDebitAccountLookupBlocks(groups: MockDebitAccountGroup[]): ContentBlock[] {
  const accounts = groups.flatMap((group) => group.subAccounts);
  if (accounts.length === 0) {
    return [
      buildInfoCard('No debit accounts available', 'I could not find any debit accounts for this profile.'),
    ];
  }

  return [
    buildTextBlock(
      `I found ${accounts.length} debit account${accounts.length === 1 ? '' : 's'} across ${groups.length} account group${groups.length === 1 ? '' : 's'} you can use for domestic payments.`,
      'My debit accounts',
    ),
    buildSummaryCard('Available debit accounts', [], {
      purpose: 'debit-account-results',
      groupCount: groups.length,
      accountCount: accounts.length,
      groupPageSize: 10,
      subAccountPageSize: 10,
      groups: groups.map(debitAccountGroupMetadata),
    }),
  ];
}

function confirmationActions() {
  return [
    { id: 'CONFIRM_PAYMENT', label: 'Confirm payment' },
    { id: 'CANCEL_PAYMENT', label: 'Cancel', tone: 'secondary' },
  ];
}

function buildConfirmationBlocks(draft: PaymentDraft): ContentBlock[] {
  return [
    buildTextBlock(
      'Please confirm the payee, debit account, amount, and payment date before I submit the domestic payment.',
      'Awaiting confirmation',
    ),
    buildSummaryCard('Domestic payment summary', draftSummaryFields(draft), {
      actions: confirmationActions(),
      editableFields: [editablePaymentDateField(draft, false)],
    }),
  ];
}

function buildCompletionBlocks(draft: PaymentDraft): ContentBlock[] {
  return [
    buildInfoCard(
      'Payment submitted',
      `The domestic payment to ${draft.selectedPayee?.name ?? 'the selected payee'} was submitted successfully.`,
    ),
    buildSummaryCard('Completed payment', [
      ...draftSummaryFields(draft),
      { label: 'Reference', value: draft.downstreamReference ?? 'Pending' },
    ]),
  ];
}

function buildCancellationBlocks(draft: PaymentDraft): ContentBlock[] {
  return [
    buildInfoCard(
      'Payment cancelled',
      `The domestic payment draft for ${draft.selectedPayee?.name ?? draft.payeeQueryText ?? 'the selected payee'} was cancelled.`,
    ),
    buildSummaryCard('Cancelled draft', draftSummaryFields(draft)),
  ];
}

function buildUnsupportedBlocks(): ContentBlock[] {
  return [
    buildInfoCard(
      'Not supported in V1',
      'This POC currently supports registered payee lookup and domestic payment to a registered payee only.',
    ),
  ];
}

function buildLookupBlocks(query: string | null, matches: RegisteredPayee[]): ContentBlock[] {
  if (matches.length === 0) {
    return [
      buildInfoCard(
        'No registered payees found',
        query
          ? `I could not find a registered payee matching "${query}".`
          : 'There are no registered payees available in this mock profile.',
      ),
    ];
  }

  const contacts = groupPayeesByContact(matches);
  const totalAccounts = matches.length;
  const headline = query
    ? `I found ${contacts.length} registered payee${contacts.length === 1 ? '' : 's'} (${totalAccounts} ${
        totalAccounts === 1 ? 'account' : 'accounts'
      }) matching "${query}".`
    : `I found ${contacts.length} registered payee${contacts.length === 1 ? '' : 's'} with ${totalAccounts} ${
        totalAccounts === 1 ? 'account' : 'accounts'
      } in total.`;

  return [
    buildTextBlock(headline, 'Registered payees'),
    buildSummaryCard(
      contacts.length === 1 ? 'Registered payee' : 'Registered payee results',
      [],
      {
        purpose: 'registered-payee-results',
        payeeCount: contacts.length,
        accountCount: totalAccounts,
        payees: contacts.map(payeeContactMetadata),
        payeePageSize: 10,
        accountPageSize: 10,
      },
    ),
  ];
}

function extractAmount(text: string) {
  const match = text.replaceAll(',', '').match(/(?:hkd\s*)?(\d+(?:\.\d{1,2})?)/i);
  if (!match) {
    return null;
  }

  const parsed = Number(match[1]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function extractPaymentDate(text: string) {
  const normalized = normalize(text);

  if (normalized.includes('tomorrow') || normalized.includes('later')) {
    return futureDate(1);
  }

  if (normalized.includes('today') || normalized.includes('now')) {
    return todayDate();
  }

  const isoDate = text.match(/\b(\d{4}-\d{2}-\d{2})\b/);
  if (isoDate?.[1]) {
    return isoDate[1];
  }

  return null;
}

function aliasMatchesText(text: string) {
  const normalizedText = normalize(text);
  return [...new Set(REGISTERED_PAYEES.flatMap((payee) => payee.aliases))]
    .sort((left, right) => right.length - left.length)
    .find((alias) => normalizedText.includes(alias));
}

function sanitizePayeeQuery(value: string) {
  return normalize(value)
    .replace(/\b(hkd|today|tomorrow|now|later|please|thanks|registered|payee|payees)\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function extractPayeeQuery(text: string) {
  const alias = aliasMatchesText(text);
  if (alias) {
    return alias;
  }

  const patterns = [
    /(?:pay|send|transfer)(?:\s+to)?\s+(.+?)(?=\s+\d|\s+hkd|\s+today|\s+tomorrow|\s+later|$)/i,
    /(?:find|lookup|look up|check|show)(?:\s+my)?(?:\s+registered)?(?:\s+payees?|\s+payee)?(?:\s+for)?\s+(.+)/i,
    /do i have\s+(.+?)\s+(?:registered|as a payee)/i,
  ];

  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match?.[1]) {
      const sanitized = sanitizePayeeQuery(match[1]);
      if (sanitized) {
        return sanitized;
      }
    }
  }

  return null;
}

function matchPayees(query: string | null) {
  if (!query) {
    return [];
  }

  const normalizedQuery = normalize(query);
  return REGISTERED_PAYEES.filter(
    (payee) =>
      normalize(payee.name).includes(normalizedQuery) ||
      normalize(payee.contactFullName).includes(normalizedQuery) ||
      payee.aliases.some(
        (alias) => alias.includes(normalizedQuery) || normalizedQuery.includes(alias),
      ),
  );
}

type MockPayeeContact = {
  contactId: string;
  nickName: string;
  contactFullName: string;
  accounts: RegisteredPayee[];
};

function groupPayeesByContact(payees: RegisteredPayee[]): MockPayeeContact[] {
  const byContact = new Map<string, MockPayeeContact>();
  for (const p of payees) {
    let existing = byContact.get(p.contactId);
    if (!existing) {
      existing = {
        contactId: p.contactId,
        nickName: p.name,
        contactFullName: p.contactFullName,
        accounts: [],
      };
      byContact.set(p.contactId, existing);
    }
    existing.accounts.push(p);
  }
  return Array.from(byContact.values());
}

function payeeAccountMetadata(account: RegisteredPayee) {
  return {
    addressId: account.payeeId,
    bankCode: account.bankCode ?? '',
    bankName: account.bankName ?? '',
    accountNumber: account.accountNumber ?? '',
    accountProductType: account.accountProductType,
    payeeAccountLabel: account.payeeAccountLabel,
    accountLimit: account.accountLimit,
    accountLimitCurrency: account.accountLimitCurrency,
    remittanceCurrencyCode: account.remittanceCurrencyCode ?? account.accountLimitCurrency,
    effectiveRemittanceCurrency: account.remittanceCurrencyCode ?? account.accountLimitCurrency,
    selectable: account.payeeAccountLabel !== 'International',
  };
}

function payeeContactMetadata(contact: MockPayeeContact) {
  return {
    contactId: contact.contactId,
    nickName: contact.nickName,
    contactFullName: contact.contactFullName,
    accountCount: contact.accounts.length,
    accounts: contact.accounts.map(payeeAccountMetadata),
  };
}

function isInternationalIntent(text: string) {
  return /\b(international|overseas|swift|wire)\b/i.test(text);
}

function isPayeeLookupIntent(text: string) {
  return /\b(payee|registered|lookup|look up|find|show)\b/i.test(text) || /do i have/i.test(text);
}

function isDebitAccountLookupIntent(text: string) {
  return /\b(list|show|view|check)\s+my\s+(accounts?|debit accounts?|source accounts?)\b/i.test(text)
    || /\bmy\s+(accounts?|debit accounts?|source accounts?)\b/i.test(text)
    || /\b(debit account|source account)\b/i.test(text);
}

function isPaymentIntent(text: string) {
  return /\b(pay|send|transfer)\b/i.test(text);
}

function isPositiveConfirmation(text: string) {
  return /\b(confirm|confirmed|yes|okay|ok|go ahead|proceed|send it)\b/i.test(text);
}

function isCancellation(text: string) {
  return /\b(cancel|stop|never mind|don'?t|do not)\b/i.test(text);
}

function selectedDebitAccountById(accountId: string | null | undefined) {
  if (!accountId) return null;
  const found = allDebitAccounts().find((account) => account.accountId === accountId);
  return found
    ? {
        accountId: found.accountId,
        accountDisplay: found.accountDisplay,
        productCategoryCode: found.productCategoryCode,
        productDescription: found.productDescription,
        displayLabel: found.displayLabel,
        currency: found.currency ?? null,
      }
    : null;
}

function getRecord(profileId: string, sessionId: string) {
  const database = getDatabase();
  const record = database.sessionsByProfile[profileId]?.find((entry) => entry.session.sessionId === sessionId);

  if (!record) {
    throw new Error('Session not found.');
  }

  return record;
}

function setSessionTitle(record: MockSessionRecord, nextTitle: string) {
  record.session.title = nextTitle.slice(0, 120);
  record.session.updatedAt = nowIso();
}

function maybeUpdateTitleFromDraft(record: MockSessionRecord, draft: PaymentDraft) {
  if (draft.selectedPayee?.name) {
    setSessionTitle(record, `Pay ${draft.selectedPayee.name}`);
    return;
  }

  if (draft.payeeQueryText) {
    setSessionTitle(record, `Pay ${titleCaseWords(draft.payeeQueryText)}`);
  }
}

function maybeUpdateTitleFromLookup(record: MockSessionRecord, query: string | null) {
  if (query) {
    setSessionTitle(record, `Find ${titleCaseWords(query)}`);
  } else {
    setSessionTitle(record, 'Registered payees');
  }
}

function respond(record: MockSessionRecord, assistantMessage: ChatMessage, userMessage?: ChatMessage | null): ChatTurnResponse {
  const completedAssistant: ChatMessage = {
    ...assistantMessage,
    metadata: {
      ...(assistantMessage.metadata ?? {}),
      processingMs: 150 + Math.floor(Math.random() * 450),
    },
  };
  appendMessage(record, completedAssistant);

  return {
    session: clone(record.session),
    userMessage: userMessage ? clone(userMessage) : null,
    assistantMessage: clone(completedAssistant),
    activeDraft: clone(record.session.activeDraft ?? null),
    serverTimestamp: nowIso(),
  };
}

function askForMissingDetails(record: MockSessionRecord, draft: PaymentDraft, userMessage?: ChatMessage | null) {
  draft.status = 'DRAFT';
  updateDraftTimestamp(draft);
  touchSession(record, 'COLLECTING_DETAILS');
  setSessionStatus(record, 'ACTIVE');
  maybeUpdateTitleFromDraft(record, draft);
  return respond(record, buildAssistantMessage(record.session.sessionId, buildMissingDetailsBlocks(draft)), userMessage);
}

function prepareConfirmation(record: MockSessionRecord, draft: PaymentDraft, userMessage?: ChatMessage | null) {
  draft.status = 'AWAITING_CONFIRMATION';
  draft.lastError = null;
  updateDraftTimestamp(draft);
  touchSession(record, 'AWAITING_CONFIRMATION');
  setSessionStatus(record, 'ACTIVE');
  maybeUpdateTitleFromDraft(record, draft);
  return respond(record, buildAssistantMessage(record.session.sessionId, buildConfirmationBlocks(draft)), userMessage);
}

function resolveDebitAccountAndPrepareConfirmation(
  record: MockSessionRecord,
  draft: PaymentDraft,
  userMessage?: ChatMessage | null,
) {
  const availableAccounts = allDebitAccounts();
  if (availableAccounts.length === 0) {
    touchSession(record, 'COLLECTING_DETAILS');
    setSessionStatus(record, 'ACTIVE');
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildErrorCard(
          'No debit accounts available',
          'I could not find any debit accounts to fund this payment.',
        ),
      ]),
      userMessage,
    );
  }

  if (draft.selectedDebitAccount?.accountId) {
    const stillAvailable = availableAccounts.some(
      (account) => account.accountId === draft.selectedDebitAccount?.accountId,
    );
    if (stillAvailable) {
      return prepareConfirmation(record, draft, userMessage);
    }
    draft.selectedDebitAccount = null;
    updateDraftTimestamp(draft);
  }

  if (availableAccounts.length === 1) {
    draft.selectedDebitAccount = selectedDebitAccountById(availableAccounts[0].accountId);
    updateDraftTimestamp(draft);
    return prepareConfirmation(record, draft, userMessage);
  }

  touchSession(record, 'AWAITING_DEBIT_ACCOUNT_SELECTION');
  setSessionStatus(record, 'ACTIVE');
  maybeUpdateTitleFromDraft(record, draft);
  return respond(
    record,
    buildAssistantMessage(record.session.sessionId, buildDebitAccountSelectionBlocks(DEBIT_ACCOUNT_GROUPS)),
    userMessage,
  );
}

function executePayment(record: MockSessionRecord, userMessage?: ChatMessage | null) {
  const draft = record.session.activeDraft;

  if (
    !draft ||
    !draft.selectedPayee ||
    !draft.selectedDebitAccount ||
    draft.amount === null ||
    !draft.paymentDate
  ) {
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildErrorCard(
          'Unable to execute',
          'The payment draft is incomplete. Please provide the missing details first.',
        ),
      ]),
      userMessage,
    );
  }

  touchSession(record, 'EXECUTING');
  draft.status = 'EXECUTING';
  updateDraftTimestamp(draft);

  draft.status = 'CONFIRMED';
  draft.downstreamReference = `DOM-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}-${draft.draftId.slice(-4)}`;
  updateDraftTimestamp(draft);

  touchSession(record, 'COMPLETED');
  setSessionStatus(record, 'COMPLETED');

  return respond(record, buildAssistantMessage(record.session.sessionId, buildCompletionBlocks(draft)), userMessage);
}

function cancelPayment(record: MockSessionRecord, userMessage?: ChatMessage | null) {
  const draft = record.session.activeDraft;

  if (!draft) {
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildInfoCard('No active draft', 'There is no active domestic payment draft to cancel.'),
      ]),
      userMessage,
    );
  }

  draft.status = 'CANCELLED';
  updateDraftTimestamp(draft);
  touchSession(record, 'CANCELLED');
  setSessionStatus(record, 'CANCELLED');

  return respond(record, buildAssistantMessage(record.session.sessionId, buildCancellationBlocks(draft)), userMessage);
}

function continueDomesticPayment(record: MockSessionRecord, text: string, userMessage?: ChatMessage | null) {
  return applyDomesticPaymentSlots(
    record,
    {
      payeeQuery: extractPayeeQuery(text),
      amount: extractAmount(text),
      paymentDate: extractPaymentDate(text),
    },
    userMessage,
  );
}

function applyDomesticPaymentSlots(
  record: MockSessionRecord,
  slots: { payeeQuery: string | null; amount: number | null; paymentDate: string | null },
  userMessage?: ChatMessage | null,
) {
  const draft = ensureDomesticDraft(record);
  const payeeChanged = isNewPayeeQuery(draft, slots.payeeQuery);

  if (slots.payeeQuery) {
    draft.payeeQueryText = slots.payeeQuery;
    if (payeeChanged) {
      draft.selectedPayee = null;
    }
  }

  if (slots.amount !== null) {
    draft.amount = slots.amount;
  }

  if (slots.paymentDate) {
    draft.paymentDate = slots.paymentDate;
  }

  updateDraftTimestamp(draft);

  if (draft.selectedPayee) {
    if (draft.amount === null || draft.amount === undefined || !draft.paymentDate) {
      return askForMissingDetails(record, draft, userMessage);
    }

    return resolveDebitAccountAndPrepareConfirmation(record, draft, userMessage);
  }

  if (!draft.payeeQueryText) {
    return askForMissingDetails(record, draft, userMessage);
  }

  const matches = matchPayees(draft.payeeQueryText);

  if (matches.length === 0) {
    touchSession(record, 'COLLECTING_DETAILS');
    setSessionStatus(record, 'ACTIVE');
    maybeUpdateTitleFromDraft(record, draft);
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildInfoCard(
          'Registered payee not found',
          `I could not find a registered payee matching "${draft.payeeQueryText}". Please try another payee name.`,
        ),
        buildSummaryCard('Current draft', draftSummaryFields(draft), {
          editableFields: [editablePaymentDateField(draft, true)],
        }),
      ]),
      userMessage,
    );
  }

  if (matches.length > 1) {
    touchSession(record, 'AWAITING_PAYEE_SELECTION');
    setSessionStatus(record, 'ACTIVE');
    maybeUpdateTitleFromDraft(record, draft);
    return respond(
      record,
      buildAssistantMessage(
        record.session.sessionId,
        buildPayeeSelectionBlocks(draft.payeeQueryText, matches),
      ),
      userMessage,
    );
  }

  draft.selectedPayee = clone(matches[0]);
  updateDraftTimestamp(draft);

  if (draft.amount === null || draft.amount === undefined || !draft.paymentDate) {
    return askForMissingDetails(record, draft, userMessage);
  }

  return resolveDebitAccountAndPrepareConfirmation(record, draft, userMessage);
}

function isNewPayeeQuery(draft: PaymentDraft, payeeQuery: string | null) {
  if (!payeeQuery) {
    return false;
  }

  const normalized = normalize(payeeQuery);
  if (!normalized) {
    return false;
  }

  if (!draft.payeeQueryText && !draft.selectedPayee) {
    return false;
  }

  if (normalize(draft.payeeQueryText ?? '') === normalized) {
    return false;
  }

  return !draft.selectedPayee || normalize(draft.selectedPayee.name) !== normalized;
}

function handlePayeeLookup(record: MockSessionRecord, text: string, userMessage?: ChatMessage | null) {
  const query = extractPayeeQuery(text);
  const matches = query ? matchPayees(query) : REGISTERED_PAYEES;

  touchSession(record, 'IDLE');
  setSessionStatus(record, 'ACTIVE');
  maybeUpdateTitleFromLookup(record, query);

  return respond(
    record,
    buildAssistantMessage(record.session.sessionId, buildLookupBlocks(query, matches)),
    userMessage,
  );
}

function handleDebitAccountLookup(record: MockSessionRecord, userMessage?: ChatMessage | null) {
  touchSession(record, 'IDLE');
  setSessionStatus(record, 'ACTIVE');
  setSessionTitle(record, 'My debit accounts');
  return respond(
    record,
    buildAssistantMessage(record.session.sessionId, buildDebitAccountLookupBlocks(DEBIT_ACCOUNT_GROUPS)),
    userMessage,
  );
}

function handleTextTurn(record: MockSessionRecord, text: string, userMessage?: ChatMessage | null) {
  const trimmedText = text.trim();
  const currentState = record.session.state;
  const hasDraftDetails =
    Boolean(record.session.activeDraft) &&
    (extractPayeeQuery(trimmedText) !== null ||
      extractAmount(trimmedText) !== null ||
      extractPaymentDate(trimmedText) !== null);

  if (currentState === 'AWAITING_CONFIRMATION') {
    if (isPositiveConfirmation(trimmedText)) {
      return executePayment(record, userMessage);
    }

    if (isCancellation(trimmedText)) {
      return cancelPayment(record, userMessage);
    }
  }

  if (
    currentState === 'AWAITING_DEBIT_ACCOUNT_SELECTION' &&
    record.session.activeDraft &&
    !isPaymentIntent(trimmedText) &&
    !isDebitAccountLookupIntent(trimmedText) &&
    !hasDraftDetails
  ) {
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildInfoCard(
          'Select a debit account',
          'Please choose one of the available debit accounts before I continue.',
        ),
      ]),
      userMessage,
    );
  }

  if (
    currentState === 'AWAITING_PAYEE_SELECTION' &&
    record.session.activeDraft &&
    !isPaymentIntent(trimmedText) &&
    !isPayeeLookupIntent(trimmedText) &&
    !hasDraftDetails
  ) {
    return respond(
      record,
      buildAssistantMessage(record.session.sessionId, [
        buildInfoCard(
          'Select a payee',
          'Please choose one of the registered payees from the selection list before I continue.',
        ),
      ]),
      userMessage,
    );
  }

  if (isInternationalIntent(trimmedText)) {
    touchSession(record, 'IDLE');
    setSessionStatus(record, 'ACTIVE');
    return respond(record, buildAssistantMessage(record.session.sessionId, buildUnsupportedBlocks()), userMessage);
  }

  if (isDebitAccountLookupIntent(trimmedText)) {
    return handleDebitAccountLookup(record, userMessage);
  }

  if (isPaymentIntent(trimmedText) || hasDraftDetails) {
    return continueDomesticPayment(record, trimmedText, userMessage);
  }

  if (isPayeeLookupIntent(trimmedText)) {
    return handlePayeeLookup(record, trimmedText, userMessage);
  }

  return respond(
    record,
    buildAssistantMessage(record.session.sessionId, [
      buildInfoCard(
        'Try a supported request',
        'Ask me to find a registered payee, or tell me who to pay, how much, and whether it should go today or tomorrow.',
      ),
    ]),
    userMessage,
  );
}

function handleUiTurn(record: MockSessionRecord, request: UiEventRequest, userMessage?: ChatMessage | null) {
  if (request.eventType === 'SELECT_ITEM') {
    const selectedId = request.selectedItemId;

    if (!selectedId) {
      return respond(
        record,
        buildAssistantMessage(record.session.sessionId, [
          buildErrorCard('Invalid selection', 'No item was selected.'),
        ]),
        userMessage,
      );
    }

    if (record.session.state === 'AWAITING_DEBIT_ACCOUNT_SELECTION' && record.session.activeDraft) {
      const selectedDebitAccount = selectedDebitAccountById(selectedId);
      if (!selectedDebitAccount) {
        return respond(
          record,
          buildAssistantMessage(record.session.sessionId, [
            buildErrorCard('Selection expired', 'The selected debit account is no longer available.'),
          ]),
          userMessage,
        );
      }

      record.session.activeDraft.selectedDebitAccount = selectedDebitAccount;
      updateDraftTimestamp(record.session.activeDraft);
      return prepareConfirmation(record, record.session.activeDraft, userMessage);
    }

    const selectedPayee = REGISTERED_PAYEES.find((payee) => payee.payeeId === selectedId);

    if (!selectedPayee || !record.session.activeDraft) {
      return respond(
        record,
        buildAssistantMessage(record.session.sessionId, [
          buildErrorCard('Selection expired', 'The selected payee account is no longer available.'),
        ]),
        userMessage,
      );
    }

    if (selectedPayee.payeeAccountLabel === 'International') {
      return respond(
        record,
        buildAssistantMessage(record.session.sessionId, [
          buildInfoCard(
            'Cross-border payment not supported',
            'The selected account is for cross-border / international payment, which is not supported in this POC. Please pick a Local account.',
          ),
        ]),
        userMessage,
      );
    }

    const payeeChanged = Boolean(
      record.session.activeDraft.selectedPayee &&
        record.session.activeDraft.selectedPayee.payeeId !== selectedPayee.payeeId,
    );
    record.session.activeDraft.selectedPayee = clone(selectedPayee);
    updateDraftTimestamp(record.session.activeDraft);
    if (
      record.session.activeDraft.amount === null ||
      record.session.activeDraft.amount === undefined ||
      !record.session.activeDraft.paymentDate
    ) {
      return askForMissingDetails(record, record.session.activeDraft, userMessage);
    }

    return resolveDebitAccountAndPrepareConfirmation(record, record.session.activeDraft, userMessage);
  }

  if (request.eventType === 'CLICK_ACTION') {
    const action = request.actionValue;

    if (action === 'CONFIRM_PAYMENT') {
      const pickedDate = request.formValues?.paymentDate?.trim();
      if (pickedDate && record.session.activeDraft && /^\d{4}-\d{2}-\d{2}$/.test(pickedDate)) {
        record.session.activeDraft.paymentDate = pickedDate;
        updateDraftTimestamp(record.session.activeDraft);
      }
      return executePayment(record, userMessage);
    }

    if (action === 'CANCEL_PAYMENT') {
      return cancelPayment(record, userMessage);
    }
  }

  if (request.eventType === 'SUBMIT_FORM' && record.session.activeDraft) {
    const payeeQuery = request.formValues?.payee?.trim();
    const amountText = request.formValues?.amount?.trim();
    const paymentDate = request.formValues?.paymentDate?.trim();

    let amount: number | null = null;
    if (amountText) {
      const parsedAmount = Number(amountText);
      if (Number.isFinite(parsedAmount) && parsedAmount > 0) {
        amount = parsedAmount;
      }
    }

    return applyDomesticPaymentSlots(
      record,
      {
        payeeQuery: payeeQuery || null,
        amount,
        paymentDate: paymentDate || null,
      },
      userMessage,
    );
  }

  return respond(
    record,
    buildAssistantMessage(record.session.sessionId, [
      buildInfoCard('Nothing changed', 'That action is not available in the current conversation state.'),
    ]),
    userMessage,
  );
}

function defaultProfiles(): ProfileSummary[] {
  return [
    {
      id: 'profile_victor',
      guid: 'mock-profile-victor-guid',
      permNetId: '11114418_O88',
      code: 'HK_STAFF_001',
      username: 'payment10',
      displayName: 'Victor Zhong',
      avatarUrl: null,
      locale: 'en-HK',
      status: 'ACTIVE',
      supportedCapabilities: DEFAULT_CAPABILITIES,
    },
    {
      id: 'profile_iris',
      guid: 'mock-profile-iris-guid',
      permNetId: '11114418_O89',
      code: 'HK_OPS_014',
      username: 'payment14',
      displayName: 'Iris Leung',
      avatarUrl: null,
      locale: 'en-HK',
      status: 'ACTIVE',
      supportedCapabilities: DEFAULT_CAPABILITIES,
    },
    {
      id: 'profile_marcus',
      guid: 'mock-profile-marcus-guid',
      permNetId: '11114418_O90',
      code: 'HK_FIN_021',
      username: 'payment21',
      displayName: 'Marcus Ng',
      avatarUrl: null,
      locale: 'en-HK',
      status: 'ACTIVE',
      supportedCapabilities: DEFAULT_CAPABILITIES,
    },
  ];
}

function makeDatabase(): MockDatabase {
  const profiles = defaultProfiles();
  return {
    profiles,
    sessionsByProfile: Object.fromEntries(profiles.map((profile) => [profile.id, []])),
  };
}

function getDatabase() {
  if (memoryDb) {
    return memoryDb;
  }

  const persisted = readStorage(STORAGE_KEY);

  if (persisted) {
    memoryDb = JSON.parse(persisted) as MockDatabase;
    return memoryDb;
  }

  memoryDb = makeDatabase();
  writeStorage(STORAGE_KEY, JSON.stringify(memoryDb));
  return memoryDb;
}

function persistDatabase() {
  if (memoryDb) {
    writeStorage(STORAGE_KEY, JSON.stringify(memoryDb));
  }
}

export function resetMockData() {
  memoryDb = makeDatabase();
  persistDatabase();
}

export function listProfiles() {
  return withLatency(() => clone(getDatabase().profiles));
}

export function profileLogin(request: ProfileLoginRequest) {
  return withLatency<CurrentUserContext>(() => {
    const database = getDatabase();
    const profile = database.profiles.find((entry) => entry.id === request.profileId);

    if (!profile) {
      throw new Error('Profile not found.');
    }

    if (request.password !== POC_ACCESS_PASSWORD) {
      throw new Error('Invalid POC access password.');
    }

    return {
      profileId: profile.id,
      username: profile.username,
      displayName: profile.displayName,
      avatarUrl: profile.avatarUrl,
      locale: profile.locale,
      loginMode: 'PROFILE_SELECTION',
      supportedCapabilities: clone(profile.supportedCapabilities),
    };
  });
}

export function listChatSessions(profileId: string) {
  return withLatency<ChatSessionSummary[]>(() => {
    const database = getDatabase();
    const records = database.sessionsByProfile[profileId] ?? [];

    return clone(
      [...records]
        .sort((left, right) => right.session.updatedAt.localeCompare(left.session.updatedAt))
        .map((record) => ({
          sessionId: record.session.sessionId,
          title: record.session.title,
          titleLocked: record.session.titleLocked ?? false,
          status: record.session.status,
          state: record.session.state,
          llmProvider: record.session.llmProvider ?? null,
          lastMessagePreview: record.session.lastMessagePreview ?? null,
          createdAt: record.session.createdAt,
          updatedAt: record.session.updatedAt,
        })),
    );
  });
}

export function createChatSession(profileId: string, title = 'New conversation') {
  return withLatency<ChatSessionDetail>(() => {
    const database = getDatabase();
    const createdAt = nowIso();
    const session: ChatSessionDetail = {
      sessionId: createId('session'),
      title,
      titleLocked: false,
      status: 'ACTIVE',
      state: 'IDLE',
      llmProvider: 'COPILOT_PERSONAL',
      lastMessagePreview: null,
      createdAt,
      updatedAt: createdAt,
      activeDraft: null,
    };

    const record: MockSessionRecord = {
      profileId,
      session,
      messages: [],
    };

    const welcomeMessage = buildAssistantMessage(session.sessionId, buildWelcomeBlocks());
    appendMessage(record, welcomeMessage);

    database.sessionsByProfile[profileId] = [record, ...(database.sessionsByProfile[profileId] ?? [])];
    persistDatabase();
    return clone(record.session);
  });
}

export function getChatSession(profileId: string, sessionId: string) {
  return withLatency<ChatSessionDetail>(() => clone(getRecord(profileId, sessionId).session));
}

export function deleteChatSession(profileId: string, sessionId: string) {
  return withLatency<void>(() => {
    const database = getDatabase();
    const records = database.sessionsByProfile[profileId] ?? [];
    const next = records.filter((entry) => entry.session.sessionId !== sessionId);
    if (next.length === records.length) {
      throw new Error('Session not found.');
    }
    database.sessionsByProfile[profileId] = next;
    persistDatabase();
  });
}

export function renameChatSession(profileId: string, sessionId: string, title: string) {
  return withLatency<ChatSessionDetail>(() => {
    const trimmed = (title ?? '').trim();
    if (!trimmed) throw new Error('Session title must not be blank.');
    const record = getRecord(profileId, sessionId);
    record.session.title = trimmed.slice(0, 120);
    record.session.titleLocked = true;
    record.session.updatedAt = nowIso();
    persistDatabase();
    return clone(record.session);
  });
}

export function listChatMessages(profileId: string, sessionId: string) {
  return withLatency<ChatMessage[]>(() => clone(getRecord(profileId, sessionId).messages));
}

export function sendChatMessage(profileId: string, sessionId: string, request: SendMessageRequest) {
  return withLatency<ChatTurnResponse>(() => {
    const record = getRecord(profileId, sessionId);
    const trimmedText = request.messageText.trim();

    if (!trimmedText) {
      throw new Error('Message text is required.');
    }

    const userMessage = buildUserTextMessage(sessionId, trimmedText);
    appendMessage(record, userMessage);

    const response = handleTextTurn(record, trimmedText, userMessage);
    persistDatabase();
    return response;
  });
}

export function submitUiEvent(profileId: string, sessionId: string, request: UiEventRequest) {
  return withLatency<ChatTurnResponse>(() => {
    const record = getRecord(profileId, sessionId);
    const userText = describeUiEvent(record, request);

    const userMessage = buildUserEventMessage(sessionId, userText);
    appendMessage(record, userMessage);

    const response = handleUiTurn(record, request, userMessage);
    persistDatabase();
    return response;
  });
}
