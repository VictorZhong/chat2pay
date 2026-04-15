export type ProfileStatus = 'ACTIVE' | 'INACTIVE';

export type ChatSessionStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'ARCHIVED';

export type WorkflowState =
  | 'IDLE'
  | 'COLLECTING_TRANSFER_INFO'
  | 'RESOLVING_AMBIGUITY'
  | 'READY_FOR_PAYMENT_OPTIONS'
  | 'READY_FOR_LIMIT_CHECK'
  | 'READY_FOR_PROPOSE'
  | 'AWAITING_USER_CONFIRMATION'
  | 'READY_FOR_CONFIRM'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export type TransferStatus = 'DRAFT' | 'PROPOSED' | 'CONFIRMED' | 'FAILED' | 'CANCELLED';

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';

export type MessageType = 'TEXT' | 'CARD' | 'LIST' | 'FORM' | 'UI_EVENT';

export type UiEventType = 'SELECT_ITEM' | 'SUBMIT_FORM' | 'CLICK_ACTION';

export type PaymentRail = 'GDLV' | 'GDRIA' | 'ORTT' | null;

export type LimitCheckStatus = 'NOT_STARTED' | 'PASSED' | 'FAILED' | null;

export interface ProfileSummary {
  id: string;
  code: string;
  displayName: string;
  username: string;
  avatarUrl: string | null;
  mockCustomerId: string;
  locale: string;
  status: ProfileStatus;
}

export interface CurrentUserContext {
  profileId: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  locale: string;
  loginMode: 'PROFILE_SELECTION';
}

export interface DisplayField {
  label: string;
  value: string;
}

export interface SelectableItem {
  itemId: string;
  label: string;
  description?: string | null;
  value?: string | null;
  metadata?: Record<string, unknown>;
}

export interface FormField {
  fieldId: string;
  label: string;
  fieldType: 'TEXT' | 'NUMBER' | 'CURRENCY' | 'SELECT';
  required?: boolean;
  placeholder?: string | null;
  options?: SelectableItem[];
}

export interface TextBlock {
  blockId: string;
  type: 'TEXT';
  title?: string | null;
  text: string;
  metadata?: Record<string, unknown>;
}

export interface SummaryCardBlock {
  blockId: string;
  type: 'SUMMARY_CARD';
  title: string;
  fields: DisplayField[];
  metadata?: Record<string, unknown>;
}

export interface SelectableListBlock {
  blockId: string;
  type: 'SELECTABLE_LIST';
  title: string;
  selectionMode: 'SINGLE' | 'MULTI';
  items: SelectableItem[];
  metadata?: Record<string, unknown>;
}

export interface SimpleFormBlock {
  blockId: string;
  type: 'SIMPLE_FORM';
  title: string;
  fields: FormField[];
  submitLabel?: string | null;
  metadata?: Record<string, unknown>;
}

export interface ErrorCardBlock {
  blockId: string;
  type: 'ERROR_CARD';
  title: string;
  text: string;
  metadata?: Record<string, unknown>;
}

export interface InfoCardBlock {
  blockId: string;
  type: 'INFO_CARD';
  title: string;
  text: string;
  metadata?: Record<string, unknown>;
}

export type ContentBlock =
  | TextBlock
  | SummaryCardBlock
  | SelectableListBlock
  | SimpleFormBlock
  | ErrorCardBlock
  | InfoCardBlock;

export interface ChatMessage {
  messageId: string;
  sessionId: string;
  role: MessageRole;
  messageType: MessageType;
  text?: string | null;
  contentBlocks?: ContentBlock[];
  createdAt: string;
}

export interface TransactionDraft {
  draftId: string;
  sessionId: string;
  status: TransferStatus;
  workflowState: WorkflowState;
  sourceAccountId?: string | null;
  sourceAccountDisplay?: string | null;
  payeeId?: string | null;
  payeeDisplay?: string | null;
  amount?: number | null;
  currency?: string | null;
  paymentRail: PaymentRail;
  note?: string | null;
  limitCheckStatus: LimitCheckStatus;
  proposalId?: string | null;
  proposalSummary?: Record<string, unknown> | null;
  transferReference?: string | null;
  lastUpdatedAt: string;
}

export interface ChatSessionSummary {
  sessionId: string;
  title: string;
  status: ChatSessionStatus;
  workflowState: WorkflowState;
  lastAssistantText?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatSessionDetail {
  sessionId: string;
  title: string;
  status: ChatSessionStatus;
  workflowState: WorkflowState;
  createdAt: string;
  updatedAt: string;
  activeDraft?: TransactionDraft | null;
}

export interface ChatSessionSummaryPage {
  items: ChatSessionSummary[];
  page: number;
  pageSize: number;
  total: number;
}

export interface ChatMessagePage {
  items: ChatMessage[];
  page: number;
  pageSize: number;
  total: number;
}

export interface ChatSessionCreateResponse {
  session: ChatSessionDetail;
  assistantMessages: ChatMessage[];
}

export interface SendMessageRequest {
  messageText: string;
  clientMessageId?: string | null;
}

export interface UiEventRequest {
  eventType: UiEventType;
  sourceMessageId: string;
  sourceBlockId: string;
  selectedItemIds?: string[];
  formValues?: Record<string, string>;
  clientEventId?: string | null;
}

export interface SuggestedAction {
  actionType:
    | 'SEND_TEXT_HINT'
    | 'SELECT_FROM_LIST'
    | 'SUBMIT_FORM'
    | 'CONFIRM_TRANSFER'
    | 'CANCEL_TRANSFER'
    | 'START_NEW_CHAT';
  label: string;
  value?: string | null;
}

export interface ChatTurnResponse {
  session: ChatSessionDetail;
  userEchoMessage?: ChatMessage | null;
  assistantMessages: ChatMessage[];
  draftSummary?: TransactionDraft | null;
  workflowState: WorkflowState;
  suggestedActions?: SuggestedAction[];
  serverTimestamp: string;
}

export interface ErrorResponse {
  code: string;
  message: string;
  details?: string[];
  timestamp: string;
}
