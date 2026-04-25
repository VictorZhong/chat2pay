export type ProfileStatus = 'ACTIVE' | 'INACTIVE';

export type CapabilityType =
  | 'REGISTERED_PAYEE_LOOKUP'
  | 'DOMESTIC_PAYMENT'
  | 'INTERNATIONAL_PAYMENT';

export type ChatSessionStatus = 'ACTIVE' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'ARCHIVED';

export type ConversationState =
  | 'IDLE'
  | 'COLLECTING_DETAILS'
  | 'AWAITING_PAYEE_SELECTION'
  | 'AWAITING_CONFIRMATION'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export type PaymentDraftStatus =
  | 'DRAFT'
  | 'AWAITING_CONFIRMATION'
  | 'EXECUTING'
  | 'CONFIRMED'
  | 'FAILED'
  | 'CANCELLED';

export type PaymentType = 'DOMESTIC_PAYMENT' | 'INTERNATIONAL_PAYMENT';

export type LlmProviderType = 'COPILOT_PERSONAL' | 'REMOTE_API';

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';

export type MessageKind = 'TEXT' | 'BLOCKS' | 'UI_EVENT' | 'SYSTEM';

export type UiEventType = 'SELECT_ITEM' | 'SUBMIT_FORM' | 'CLICK_ACTION';

export interface ProfileSummary {
  id: string;
  guid: string | null;
  permNetId: string | null;
  code: string;
  displayName: string;
  username: string;
  avatarUrl: string | null;
  locale: string;
  status: ProfileStatus;
  supportedCapabilities: CapabilityType[];
}

export interface CurrentUserContext {
  profileId: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  locale: string;
  loginMode: 'PROFILE_SELECTION';
  supportedCapabilities: CapabilityType[];
}

export interface ProfileLoginRequest {
  profileId: string;
  password: string;
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
  kind: MessageKind;
  text?: string | null;
  contentBlocks?: ContentBlock[] | null;
  metadata?: Record<string, unknown> | null;
  createdAt: string;
}

export interface PayeeSummary {
  payeeId: string;
  name: string;
  payeeType?: string | null;
  bankCode?: string | null;
  bankName?: string | null;
  accountNumber?: string | null;
  displayLabel?: string | null;
}

export interface ErrorSummary {
  code: string;
  message: string;
}

export interface PaymentDraft {
  draftId: string;
  sessionId: string;
  paymentType: PaymentType;
  status: PaymentDraftStatus;
  payeeQueryText?: string | null;
  selectedPayee?: PayeeSummary | null;
  amount?: number | null;
  currency?: string | null;
  paymentDate?: string | null;
  downstreamReference?: string | null;
  lastError?: ErrorSummary | null;
  context?: Record<string, unknown> | null;
  lastUpdatedAt: string;
}

export interface ChatSessionSummary {
  sessionId: string;
  title: string;
  status: ChatSessionStatus;
  state: ConversationState;
  llmProvider?: LlmProviderType | null;
  lastMessagePreview?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatSessionDetail {
  sessionId: string;
  title: string;
  status: ChatSessionStatus;
  state: ConversationState;
  llmProvider?: LlmProviderType | null;
  lastMessagePreview?: string | null;
  createdAt: string;
  updatedAt: string;
  activeDraft?: PaymentDraft | null;
}

export interface SendMessageRequest {
  messageText: string;
  clientMessageId?: string | null;
  stream?: boolean;
}

export interface UiEventRequest {
  eventType: UiEventType;
  sourceMessageId: string;
  sourceBlockId: string;
  selectedItemId?: string | null;
  actionValue?: string | null;
  formValues?: Record<string, string>;
  clientEventId?: string | null;
  stream?: boolean;
}

export interface ChatTurnResponse {
  session: ChatSessionDetail;
  userMessage?: ChatMessage | null;
  assistantMessage: ChatMessage;
  activeDraft?: PaymentDraft | null;
  serverTimestamp: string;
}

export interface ErrorResponse {
  code: string;
  message: string;
  details?: string[];
  timestamp: string;
}
