import type {
  ChatMessage,
  ChatMessagePage,
  ChatSessionCreateResponse,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatSessionSummaryPage,
  ChatTurnResponse,
  ContentBlock,
  CurrentUserContext,
  DisplayField,
  FormField,
  PaymentRail,
  ProfileSummary,
  SelectableItem,
  SendMessageRequest,
  SuggestedAction,
  TransactionDraft,
  UiEventRequest,
  WorkflowState,
} from '@/shared/api/contracts';
import { MOCK_API_LATENCY_MS } from '@/shared/config/env';
import { createId } from '@/shared/lib/id';

type KnownPayeeId = keyof typeof KNOWN_PAYEES;
type ParsedPayee = KnownPayeeId | 'ambiguous_tom' | undefined;

type MockSessionRecord = {
  profileId: string;
  session: ChatSessionDetail;
  messages: ChatMessage[];
};

type MockDatabase = {
  profiles: ProfileSummary[];
  sessionsByProfile: Record<string, MockSessionRecord[]>;
};

const STORAGE_KEY = 'chat2pay-mock-db-v1';
let memoryDb: MockDatabase | null = null;
const storageFallback = new Map<string, string>();

const SOURCE_ACCOUNT = {
  id: 'acct_hk_primary_savings',
  display: 'Primary Savings • 123-456-001',
};

const KNOWN_PAYEES: Record<
  string,
  {
    label: string;
    description: string;
  }
> = {
  payee_tom_lee: {
    label: 'Tom Lee',
    description: 'Hang Seng Supplier Settlement • HK • Frequent payee',
  },
  payee_tom_chan: {
    label: 'Tom Chan',
    description: 'Tom Chan Trading • SG • Newly added payee',
  },
  payee_sarah_wong: {
    label: 'Sarah Wong',
    description: 'Sarah Wong • Internal staff reimbursement',
  },
};

const PAYMENT_RAILS: Array<{
  id: Exclude<PaymentRail, null>;
  label: string;
  description: string;
  eta: string;
  fee: string;
}> = [
  {
    id: 'ORTT',
    label: 'ORTT',
    description: 'Fast same-day settlement for urgent transfers',
    eta: 'Today before 18:00',
    fee: 'HKD 120.00',
  },
  {
    id: 'GDLV',
    label: 'GDLV',
    description: 'Lower-cost domestic route with standard processing',
    eta: 'Today before 22:00',
    fee: 'HKD 38.00',
  },
  {
    id: 'GDRIA',
    label: 'GDRIA',
    description: 'Regional express transfer with higher fee',
    eta: 'Within 2 hours',
    fee: 'HKD 88.00',
  },
];

function withLatency<T>(factory: () => T) {
  return new Promise<T>((resolve) => {
    window.setTimeout(() => resolve(factory()), MOCK_API_LATENCY_MS);
  });
}

function nowIso() {
  return new Date().toISOString();
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
): ContentBlock {
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
    selectionMode: 'SINGLE',
    items,
    metadata,
  };
}

function buildSimpleForm(
  title: string,
  fields: FormField[],
  submitLabel = 'Submit',
  metadata?: Record<string, unknown>,
): ContentBlock {
  return {
    blockId: createId('blk_form'),
    type: 'SIMPLE_FORM',
    title,
    fields,
    submitLabel,
    metadata,
  };
}

function buildAssistantMessage(sessionId: string, blocks: ContentBlock[], text?: string) {
  return buildMessage({
    sessionId,
    role: 'ASSISTANT',
    messageType: inferMessageType(blocks),
    text,
    contentBlocks: blocks,
  });
}

function buildUserTextMessage(sessionId: string, text: string) {
  return buildMessage({
    sessionId,
    role: 'USER',
    messageType: 'TEXT',
    text,
  });
}

function buildUserEventMessage(sessionId: string, text: string) {
  return buildMessage({
    sessionId,
    role: 'USER',
    messageType: 'UI_EVENT',
    text,
  });
}

function buildMessage(input: Omit<ChatMessage, 'messageId' | 'createdAt'>): ChatMessage {
  return {
    ...input,
    messageId: createId('msg'),
    createdAt: nowIso(),
  };
}

function inferMessageType(blocks: ContentBlock[]): ChatMessage['messageType'] {
  if (blocks.some((block) => block.type === 'SIMPLE_FORM')) {
    return 'FORM';
  }

  if (blocks.some((block) => block.type === 'SELECTABLE_LIST')) {
    return 'LIST';
  }

  if (blocks.some((block) => block.type !== 'TEXT')) {
    return 'CARD';
  }

  return 'TEXT';
}

function buildEmptyDraft(sessionId: string): TransactionDraft {
  return {
    draftId: createId('draft'),
    sessionId,
    status: 'DRAFT',
    workflowState: 'COLLECTING_TRANSFER_INFO',
    sourceAccountId: SOURCE_ACCOUNT.id,
    sourceAccountDisplay: SOURCE_ACCOUNT.display,
    paymentRail: null,
    limitCheckStatus: 'NOT_STARTED',
    lastUpdatedAt: nowIso(),
  };
}

function ensureDraft(record: MockSessionRecord) {
  if (!record.session.activeDraft) {
    record.session.activeDraft = buildEmptyDraft(record.session.sessionId);
  }

  return record.session.activeDraft;
}

function touchSession(record: MockSessionRecord, workflowState: WorkflowState) {
  const timestamp = nowIso();
  record.session.workflowState = workflowState;
  record.session.updatedAt = timestamp;

  if (record.session.activeDraft) {
    record.session.activeDraft.workflowState = workflowState;
    record.session.activeDraft.lastUpdatedAt = timestamp;
  }
}

function parseAmount(text: string) {
  const amountMatch = text.match(/(\d[\d,]*(?:\.\d{1,2})?)/);
  return amountMatch ? Number(amountMatch[1].replaceAll(',', '')) : undefined;
}

function parseCurrency(text: string) {
  const currencyMatch = text.match(/\b(HKD|USD|SGD|CNY)\b/i);
  return currencyMatch?.[1]?.toUpperCase();
}

function parsePayee(text: string): ParsedPayee {
  if (/\btom lee\b/i.test(text)) {
    return 'payee_tom_lee';
  }

  if (/\btom chan\b/i.test(text)) {
    return 'payee_tom_chan';
  }

  if (/\bsarah\b/i.test(text)) {
    return 'payee_sarah_wong';
  }

  if (/\btom\b/i.test(text)) {
    return 'ambiguous_tom';
  }

  return undefined;
}

function parseNote(text: string) {
  const noteMatch = text.match(/\bfor\s+(.+)$/i);
  return noteMatch?.[1]?.trim();
}

function parsePaymentRail(text: string): PaymentRail {
  const rail = PAYMENT_RAILS.find((item) => text.toUpperCase().includes(item.id));
  return rail?.id ?? null;
}

function isConfirmIntent(text: string) {
  return /\b(confirm|approve|yes)\b/i.test(text);
}

function isCancelIntent(text: string) {
  return /\b(cancel|stop|abort|no)\b/i.test(text);
}

function isTransferIntent(text: string) {
  return /\b(pay|transfer|send|remit)\b/i.test(text);
}

function extractDisplayText(message: ChatMessage) {
  if (message.text) {
    return message.text;
  }

  const firstBlock = message.contentBlocks?.[0];

  if (!firstBlock) {
    return null;
  }

  if (firstBlock.type === 'TEXT') {
    return firstBlock.text;
  }

  if (firstBlock.type === 'INFO_CARD' || firstBlock.type === 'ERROR_CARD') {
    return firstBlock.text;
  }

  return firstBlock.title;
}

function deriveSessionSummary(record: MockSessionRecord): ChatSessionSummary {
  const lastAssistantMessage = [...record.messages]
    .reverse()
    .find((message) => message.role === 'ASSISTANT');

  return {
    sessionId: record.session.sessionId,
    title: record.session.title,
    status: record.session.status,
    workflowState: record.session.workflowState,
    lastAssistantText: lastAssistantMessage ? extractDisplayText(lastAssistantMessage) : null,
    createdAt: record.session.createdAt,
    updatedAt: record.session.updatedAt,
  };
}

function proposalFields(record: MockSessionRecord): DisplayField[] {
  const draft = ensureDraft(record);
  const railConfig = PAYMENT_RAILS.find((item) => item.id === draft.paymentRail) ?? PAYMENT_RAILS[0];

  return [
    { label: 'Source account', value: draft.sourceAccountDisplay ?? SOURCE_ACCOUNT.display },
    { label: 'Payee', value: draft.payeeDisplay ?? 'Pending' },
    {
      label: 'Amount',
      value:
        draft.amount && draft.currency
          ? `${draft.amount.toLocaleString('en-HK', { minimumFractionDigits: 2 })} ${draft.currency}`
          : 'Pending',
    },
    { label: 'Payment rail', value: railConfig.label },
    { label: 'Fee', value: railConfig.fee },
    { label: 'Estimated arrival', value: railConfig.eta },
    { label: 'Proposal ID', value: draft.proposalId ?? 'Pending' },
  ];
}

function buildWelcomeBlocks(profileName: string): ContentBlock[] {
  return [
    buildTextBlock(
      `Hi ${profileName.split(' ')[0]}, I can help you transfer money, clarify payees, and prepare a proposal before confirmation.`,
    ),
    buildInfoCard(
      'Suggested start',
      'Try "Pay Tom 5000 HKD" or start with partial details and I will guide the rest.',
    ),
  ];
}

function buildMissingDetailsBlocks(draft: TransactionDraft): ContentBlock[] {
  return [
    buildTextBlock('I need a few details before I can continue the transfer flow.'),
    buildSimpleForm(
      'Transfer details',
      [
        {
          fieldId: 'payee',
          label: 'Payee',
          fieldType: 'TEXT',
          required: true,
          placeholder: 'Tom Lee',
        },
        {
          fieldId: 'amount',
          label: 'Amount',
          fieldType: 'NUMBER',
          required: true,
          placeholder: '5000',
        },
        {
          fieldId: 'currency',
          label: 'Currency',
          fieldType: 'SELECT',
          required: true,
          options: [
            { itemId: 'HKD', label: 'HKD' },
            { itemId: 'USD', label: 'USD' },
            { itemId: 'SGD', label: 'SGD' },
          ],
        },
        {
          fieldId: 'note',
          label: 'Note',
          fieldType: 'TEXT',
          placeholder: 'Invoice 2048',
        },
      ],
      'Continue',
      {
        stage: 'COLLECT_DETAILS',
        draftId: draft.draftId,
      },
    ),
  ];
}

function buildPayeeSelectionBlocks(): ContentBlock[] {
  return [
    buildTextBlock('I found more than one payee named Tom. Select the intended payee to continue.'),
    buildSelectableList(
      'Select payee',
      [
        {
          itemId: 'payee_tom_lee',
          label: KNOWN_PAYEES.payee_tom_lee.label,
          description: KNOWN_PAYEES.payee_tom_lee.description,
        },
        {
          itemId: 'payee_tom_chan',
          label: KNOWN_PAYEES.payee_tom_chan.label,
          description: KNOWN_PAYEES.payee_tom_chan.description,
        },
      ],
      {
        stage: 'PAYEE_SELECTION',
      },
    ),
  ];
}

function buildPaymentRailBlocks(): ContentBlock[] {
  return [
    buildTextBlock('The draft is complete. Choose the payment option that best fits this transfer.'),
    buildSelectableList(
      'Payment options',
      PAYMENT_RAILS.map((rail) => ({
        itemId: rail.id,
        label: rail.label,
        description: rail.description,
      })),
      {
        stage: 'PAYMENT_RAIL_SELECTION',
      },
    ),
  ];
}

function buildProposalBlocks(record: MockSessionRecord): ContentBlock[] {
  return [
    buildInfoCard(
      'Proposal prepared',
      'Limit check passed and a proposal is ready. Review the details before confirming.',
    ),
    buildSummaryCard('Transfer summary', proposalFields(record), {
      stage: 'PROPOSAL_REVIEW',
      actions: [
        { id: 'CONFIRM_TRANSFER', label: 'Confirm', tone: 'primary' },
        { id: 'CANCEL_TRANSFER', label: 'Cancel', tone: 'secondary' },
      ],
    }),
  ];
}

function buildCompletionBlocks(record: MockSessionRecord): ContentBlock[] {
  const draft = ensureDraft(record);

  return [
    buildSummaryCard('Transfer completed', [
      { label: 'Payee', value: draft.payeeDisplay ?? 'Pending' },
      {
        label: 'Amount',
        value:
          draft.amount && draft.currency
            ? `${draft.amount.toLocaleString('en-HK', { minimumFractionDigits: 2 })} ${draft.currency}`
            : 'Pending',
      },
      { label: 'Payment rail', value: draft.paymentRail ?? 'Pending' },
      { label: 'Transfer reference', value: draft.transferReference ?? 'Pending' },
      { label: 'Status', value: draft.status },
    ]),
  ];
}

function buildCancelledBlocks(): ContentBlock[] {
  return [
    buildInfoCard(
      'Transfer cancelled',
      'The active transfer draft has been cancelled. You can start a new chat whenever you are ready.',
    ),
  ];
}

function buildReminderBlocks(): ContentBlock[] {
  return [
    buildInfoCard(
      'Awaiting confirmation',
      'Review the proposal and confirm or cancel. Free-text input is still available if you need to change the instruction.',
    ),
  ];
}

function updateDraftFromInput(draft: TransactionDraft, input: { text?: string; values?: Record<string, string> }) {
  const sourceText = input.text ?? '';
  const sourceValues = input.values ?? {};
  const amount = parseAmount(sourceText) ?? (sourceValues.amount ? Number(sourceValues.amount) : undefined);
  const currency = parseCurrency(sourceText) ?? sourceValues.currency?.toUpperCase();
  const payee = parsePayee(sourceText) ?? parsePayee(sourceValues.payee ?? '');
  const note = parseNote(sourceText) ?? sourceValues.note;

  if (amount) {
    draft.amount = amount;
  }

  if (currency) {
    draft.currency = currency;
  }

  if (note) {
    draft.note = note;
  }

  if (payee && payee !== 'ambiguous_tom') {
    draft.payeeId = payee;
    draft.payeeDisplay = KNOWN_PAYEES[payee].label;
  }

  return payee;
}

function setTitleFromDraft(record: MockSessionRecord) {
  const draft = ensureDraft(record);
  const subject = draft.payeeDisplay ?? 'New transfer';
  record.session.title = draft.payeeDisplay ? `Transfer to ${subject}` : 'New transfer';
}

function moveToProposal(record: MockSessionRecord) {
  const draft = ensureDraft(record);
  draft.status = 'PROPOSED';
  draft.limitCheckStatus = 'PASSED';
  draft.proposalId = `PROP-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}-${draft.draftId.slice(-4)}`;
  draft.proposalSummary = Object.fromEntries(proposalFields(record).map((field) => [field.label, field.value]));
  touchSession(record, 'AWAITING_USER_CONFIRMATION');

  const assistant = buildAssistantMessage(record.session.sessionId, buildProposalBlocks(record));
  record.messages.push(assistant);

  return {
    assistantMessages: [assistant],
    suggestedActions: [
      { actionType: 'CONFIRM_TRANSFER', label: 'Confirm' },
      { actionType: 'CANCEL_TRANSFER', label: 'Cancel' },
    ] satisfies SuggestedAction[],
  };
}

function moveToCompletion(record: MockSessionRecord) {
  const draft = ensureDraft(record);
  draft.status = 'CONFIRMED';
  draft.transferReference = `TXN-${draft.draftId.slice(-6).toUpperCase()}`;
  record.session.status = 'COMPLETED';
  touchSession(record, 'COMPLETED');

  const assistant = buildAssistantMessage(record.session.sessionId, buildCompletionBlocks(record));
  record.messages.push(assistant);

  return {
    assistantMessages: [assistant],
    suggestedActions: [] as SuggestedAction[],
  };
}

function moveToCancelled(record: MockSessionRecord) {
  const draft = ensureDraft(record);
  draft.status = 'CANCELLED';
  record.session.status = 'CANCELLED';
  touchSession(record, 'CANCELLED');

  const assistant = buildAssistantMessage(record.session.sessionId, buildCancelledBlocks());
  record.messages.push(assistant);

  return {
    assistantMessages: [assistant],
    suggestedActions: [{ actionType: 'START_NEW_CHAT', label: 'Start new chat' }] satisfies SuggestedAction[],
  };
}

function respondWithBlocks(
  record: MockSessionRecord,
  workflowState: WorkflowState,
  blocks: ContentBlock[],
  suggestedActions: SuggestedAction[] = [],
) {
  touchSession(record, workflowState);
  const assistant = buildAssistantMessage(record.session.sessionId, blocks);
  record.messages.push(assistant);

  return {
    assistantMessages: [assistant],
    suggestedActions,
  };
}

function advanceFromDraftState(record: MockSessionRecord) {
  const draft = ensureDraft(record);
  setTitleFromDraft(record);

  if (!draft.amount || !draft.currency) {
    return respondWithBlocks(record, 'COLLECTING_TRANSFER_INFO', buildMissingDetailsBlocks(draft));
  }

  if (!draft.payeeId || !draft.payeeDisplay) {
    return respondWithBlocks(record, 'RESOLVING_AMBIGUITY', buildPayeeSelectionBlocks());
  }

  if (!draft.paymentRail) {
    return respondWithBlocks(record, 'READY_FOR_PAYMENT_OPTIONS', buildPaymentRailBlocks());
  }

  return moveToProposal(record);
}

function processTextTurn(record: MockSessionRecord, text: string) {
  const trimmedText = text.trim();

  if (record.session.workflowState === 'AWAITING_USER_CONFIRMATION') {
    if (isConfirmIntent(trimmedText)) {
      return moveToCompletion(record);
    }

    if (isCancelIntent(trimmedText)) {
      return moveToCancelled(record);
    }

    return respondWithBlocks(
      record,
      'AWAITING_USER_CONFIRMATION',
      buildReminderBlocks(),
      [
        { actionType: 'CONFIRM_TRANSFER', label: 'Confirm' },
        { actionType: 'CANCEL_TRANSFER', label: 'Cancel' },
      ],
    );
  }

  if (record.session.status !== 'ACTIVE') {
    return respondWithBlocks(
      record,
      record.session.workflowState,
      [buildErrorCard('Session closed', 'This session is read-only. Start a new chat to continue.')],
    );
  }

  if (!isTransferIntent(trimmedText) && record.session.workflowState === 'IDLE') {
    return respondWithBlocks(record, 'IDLE', [
      buildInfoCard(
        'Transfer guidance',
        'Use a transfer instruction such as "Pay Tom 5000 HKD" or fill the guided form in a new message.',
      ),
    ]);
  }

  const draft = ensureDraft(record);
  const payee = updateDraftFromInput(draft, { text: trimmedText });

  if (payee === 'ambiguous_tom') {
    draft.payeeId = undefined;
    draft.payeeDisplay = undefined;
    setTitleFromDraft(record);
    return respondWithBlocks(record, 'RESOLVING_AMBIGUITY', buildPayeeSelectionBlocks());
  }

  const rail = parsePaymentRail(trimmedText);

  if (rail) {
    draft.paymentRail = rail;
  }

  return advanceFromDraftState(record);
}

function processUiEventTurn(record: MockSessionRecord, request: UiEventRequest) {
  const draft = ensureDraft(record);
  const stage = findSourceStage(record, request.sourceMessageId, request.sourceBlockId);

  if (request.eventType === 'SUBMIT_FORM') {
    updateDraftFromInput(draft, { values: request.formValues });
    setTitleFromDraft(record);

    if (parsePayee(request.formValues?.payee ?? '') === 'ambiguous_tom') {
      draft.payeeId = undefined;
      draft.payeeDisplay = undefined;
      return respondWithBlocks(record, 'RESOLVING_AMBIGUITY', buildPayeeSelectionBlocks());
    }

    return advanceFromDraftState(record);
  }

  if (request.eventType === 'SELECT_ITEM') {
    const selected = request.selectedItemIds?.[0];

    if (!selected) {
      return respondWithBlocks(record, record.session.workflowState, [
        buildErrorCard('Selection required', 'Select one option before continuing.'),
      ]);
    }

    if (stage === 'PAYEE_SELECTION' && selected in KNOWN_PAYEES) {
      const payeeId = selected as KnownPayeeId;
      draft.payeeId = payeeId;
      draft.payeeDisplay = KNOWN_PAYEES[payeeId].label;
      setTitleFromDraft(record);
      return advanceFromDraftState(record);
    }

    if (stage === 'PAYMENT_RAIL_SELECTION') {
      draft.paymentRail = selected as PaymentRail;
      return advanceFromDraftState(record);
    }
  }

  if (request.eventType === 'CLICK_ACTION') {
    const actionId = request.selectedItemIds?.[0];

    if (stage === 'PROPOSAL_REVIEW' && actionId === 'CONFIRM_TRANSFER') {
      return moveToCompletion(record);
    }

    if (stage === 'PROPOSAL_REVIEW' && actionId === 'CANCEL_TRANSFER') {
      return moveToCancelled(record);
    }
  }

  return respondWithBlocks(record, record.session.workflowState, [
    buildErrorCard('Unsupported event', 'The selected UI action could not be processed.'),
  ]);
}

function findSourceStage(record: MockSessionRecord, messageId: string, blockId: string) {
  const sourceMessage = record.messages.find((message) => message.messageId === messageId);
  const block = sourceMessage?.contentBlocks?.find((item) => item.blockId === blockId);
  const stage = block?.metadata?.stage;

  return typeof stage === 'string' ? stage : undefined;
}

function seedProfiles(): ProfileSummary[] {
  return [
    {
      id: 'profile_iris',
      code: 'HK_STAFF_001',
      displayName: 'Iris Chen',
      username: 'iris.chen',
      avatarUrl: null,
      mockCustomerId: 'CUST0001',
      locale: 'en-HK',
      status: 'ACTIVE',
    },
    {
      id: 'profile_tom',
      code: 'HK_RETAIL_001',
      displayName: 'Tom Lee',
      username: 'tom.lee',
      avatarUrl: null,
      mockCustomerId: 'CUST0002',
      locale: 'en-HK',
      status: 'ACTIVE',
    },
    {
      id: 'profile_sarah',
      code: 'SG_RETAIL_001',
      displayName: 'Sarah Wong',
      username: 'sarah.wong',
      avatarUrl: null,
      mockCustomerId: 'CUST0003',
      locale: 'en-SG',
      status: 'ACTIVE',
    },
    {
      id: 'profile_demo_a',
      code: 'POC_A',
      displayName: 'Demo User A',
      username: 'demo.user.a',
      avatarUrl: null,
      mockCustomerId: 'CUST0004',
      locale: 'en-HK',
      status: 'ACTIVE',
    },
    {
      id: 'profile_demo_b',
      code: 'POC_B',
      displayName: 'Demo User B',
      username: 'demo.user.b',
      avatarUrl: null,
      mockCustomerId: 'CUST0005',
      locale: 'en-HK',
      status: 'ACTIVE',
    },
  ];
}

function makeSeedSession(profileId: string, seed: 'proposal' | 'completed'): MockSessionRecord {
  const sessionId = createId(`session_${seed}`);
  const createdAt = new Date(Date.now() - (seed === 'proposal' ? 86_400_000 : 172_800_000)).toISOString();
  const draft = buildEmptyDraft(sessionId);

  draft.amount = 5000;
  draft.currency = 'HKD';
  draft.payeeId = 'payee_tom_lee';
  draft.payeeDisplay = KNOWN_PAYEES.payee_tom_lee.label;
  draft.note = seed === 'proposal' ? 'April supplier settlement' : 'Payroll adjustment';
  draft.paymentRail = 'ORTT';

  const session: ChatSessionDetail = {
    sessionId,
    title: 'Transfer to Tom Lee',
    status: seed === 'proposal' ? 'ACTIVE' : 'COMPLETED',
    workflowState: seed === 'proposal' ? 'AWAITING_USER_CONFIRMATION' : 'COMPLETED',
    createdAt,
    updatedAt: new Date(Date.now() - (seed === 'proposal' ? 3_600_000 : 144_000_000)).toISOString(),
    activeDraft: {
      ...draft,
      status: seed === 'proposal' ? 'PROPOSED' : 'CONFIRMED',
      workflowState: seed === 'proposal' ? 'AWAITING_USER_CONFIRMATION' : 'COMPLETED',
      limitCheckStatus: 'PASSED',
      proposalId: `PROP-20260416-${seed === 'proposal' ? '0101' : '0088'}`,
      transferReference: seed === 'proposal' ? null : 'TXN-448822',
      proposalSummary: {
        amount: '5,000.00 HKD',
        payee: 'Tom Lee',
        paymentRail: 'ORTT',
      },
      lastUpdatedAt: new Date(Date.now() - (seed === 'proposal' ? 3_600_000 : 144_000_000)).toISOString(),
    },
  };

  const messages: ChatMessage[] = [
    buildAssistantMessage(sessionId, buildWelcomeBlocks('Iris Chen')),
    buildUserTextMessage(sessionId, 'Pay Tom 5000 HKD'),
  ];

  messages.push(
    buildAssistantMessage(
      sessionId,
      seed === 'proposal' ? buildProposalBlocks({ profileId, session, messages }) : buildCompletionBlocks({ profileId, session, messages }),
    ),
  );

  return {
    profileId,
    session,
    messages,
  };
}

function createInitialDatabase(): MockDatabase {
  const profiles = seedProfiles();

  return {
    profiles,
    sessionsByProfile: {
      profile_iris: [makeSeedSession('profile_iris', 'proposal'), makeSeedSession('profile_iris', 'completed')],
      profile_tom: [],
      profile_sarah: [],
      profile_demo_a: [],
      profile_demo_b: [],
    },
  };
}

function loadDatabase(): MockDatabase {
  if (memoryDb) {
    return memoryDb;
  }

  const raw = readStorage(STORAGE_KEY);

  if (!raw) {
    memoryDb = createInitialDatabase();
    saveDatabase(memoryDb);
    return memoryDb;
  }

  memoryDb = JSON.parse(raw) as MockDatabase;
  return memoryDb;
}

function saveDatabase(database: MockDatabase) {
  memoryDb = database;
  writeStorage(STORAGE_KEY, JSON.stringify(database));
}

function getProfile(profileId: string) {
  const database = loadDatabase();
  const profile = database.profiles.find((item) => item.id === profileId);

  if (!profile) {
    throw new Error(`Unknown profile: ${profileId}`);
  }

  return profile;
}

function getSessionRecord(profileId: string, sessionId: string) {
  const database = loadDatabase();
  const sessions = database.sessionsByProfile[profileId] ?? [];
  const record = sessions.find((item) => item.session.sessionId === sessionId);

  if (!record) {
    throw new Error(`Unknown session: ${sessionId}`);
  }

  return { database, record };
}

function persistUpdatedRecord(database: MockDatabase, profileId: string, record: MockSessionRecord) {
  const currentSessions = database.sessionsByProfile[profileId] ?? [];
  database.sessionsByProfile[profileId] = currentSessions
    .map((item) => (item.session.sessionId === record.session.sessionId ? record : item))
    .sort((left, right) => right.session.updatedAt.localeCompare(left.session.updatedAt));
  saveDatabase(database);
}

export function resetMockData() {
  memoryDb = createInitialDatabase();
  saveDatabase(memoryDb);
}

export function listProfiles() {
  return withLatency(() => loadDatabase().profiles.filter((profile) => profile.status === 'ACTIVE'));
}

export function profileLogin(profileId: string) {
  return withLatency<CurrentUserContext>(() => {
    const profile = getProfile(profileId);

    return {
      profileId: profile.id,
      username: profile.username,
      displayName: profile.displayName,
      avatarUrl: profile.avatarUrl,
      locale: profile.locale,
      loginMode: 'PROFILE_SELECTION',
    };
  });
}

export function listChatSessions(profileId: string) {
  return withLatency<ChatSessionSummaryPage>(() => {
    const database = loadDatabase();
    const items = (database.sessionsByProfile[profileId] ?? [])
      .map(deriveSessionSummary)
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));

    return {
      items,
      page: 1,
      pageSize: 20,
      total: items.length,
    };
  });
}

export function createChatSession(profileId: string, title?: string) {
  return withLatency<ChatSessionCreateResponse>(() => {
    const database = loadDatabase();
    const profile = getProfile(profileId);
    const timestamp = nowIso();
    const sessionId = createId('session');

    const session: ChatSessionDetail = {
      sessionId,
      title: title ?? 'New transfer',
      status: 'ACTIVE',
      workflowState: 'IDLE',
      createdAt: timestamp,
      updatedAt: timestamp,
      activeDraft: null,
    };

    const assistantMessage = buildAssistantMessage(sessionId, buildWelcomeBlocks(profile.displayName));
    const record: MockSessionRecord = {
      profileId,
      session,
      messages: [assistantMessage],
    };

    database.sessionsByProfile[profileId] = [record, ...(database.sessionsByProfile[profileId] ?? [])];
    saveDatabase(database);

    return {
      session,
      assistantMessages: [assistantMessage],
    };
  });
}

export function getChatSession(profileId: string, sessionId: string) {
  return withLatency<ChatSessionDetail>(() => getSessionRecord(profileId, sessionId).record.session);
}

export function listChatMessages(profileId: string, sessionId: string) {
  return withLatency<ChatMessagePage>(() => {
    const { record } = getSessionRecord(profileId, sessionId);

    return {
      items: record.messages,
      page: 1,
      pageSize: 100,
      total: record.messages.length,
    };
  });
}

export function sendChatMessage(profileId: string, sessionId: string, request: SendMessageRequest) {
  return withLatency<ChatTurnResponse>(() => {
    const { database, record } = getSessionRecord(profileId, sessionId);
    const userEchoMessage = buildUserTextMessage(sessionId, request.messageText.trim());
    record.messages.push(userEchoMessage);

    const result = processTextTurn(record, request.messageText);
    persistUpdatedRecord(database, profileId, record);

    return {
      session: record.session,
      userEchoMessage,
      assistantMessages: result.assistantMessages,
      draftSummary: record.session.activeDraft ?? null,
      workflowState: record.session.workflowState,
      suggestedActions: result.suggestedActions,
      serverTimestamp: nowIso(),
    };
  });
}

export function submitUiEvent(profileId: string, sessionId: string, request: UiEventRequest) {
  return withLatency<ChatTurnResponse>(() => {
    const { database, record } = getSessionRecord(profileId, sessionId);
    const userEchoText =
      request.eventType === 'SUBMIT_FORM'
        ? 'Submitted transfer details'
        : request.selectedItemIds?.[0] === 'CONFIRM_TRANSFER'
          ? 'Confirm'
          : request.selectedItemIds?.[0] === 'CANCEL_TRANSFER'
            ? 'Cancel'
            : `Selected ${request.selectedItemIds?.[0] ?? 'option'}`;

    const userEchoMessage = buildUserEventMessage(sessionId, userEchoText);
    record.messages.push(userEchoMessage);

    const result = processUiEventTurn(record, request);
    persistUpdatedRecord(database, profileId, record);

    return {
      session: record.session,
      userEchoMessage,
      assistantMessages: result.assistantMessages,
      draftSummary: record.session.activeDraft ?? null,
      workflowState: record.session.workflowState,
      suggestedActions: result.suggestedActions,
      serverTimestamp: nowIso(),
    };
  });
}
