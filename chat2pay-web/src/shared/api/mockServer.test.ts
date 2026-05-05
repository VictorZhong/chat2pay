import { beforeEach, describe, expect, it } from 'vitest';
import {
  createChatSession,
  listChatMessages,
  profileLogin,
  resetMockData,
  sendChatMessage,
  submitUiEvent,
} from '@/shared/api/mockServer';

describe('mockServer domestic payment flow', () => {
  beforeEach(() => {
    resetMockData();
  });

  it('creates a new session and completes a domestic payment through payee selection and confirmation', async () => {
    const user = await profileLogin({
      profileId: 'profile_victor',
      password: 'tb123',
    });
    const created = await createChatSession(user.profileId);
    const sessionId = created.sessionId;

    const firstTurn = await sendChatMessage(user.profileId, sessionId, {
      messageText: 'Pay Bob 5000 HKD today',
    });

    expect(firstTurn.session.state).toBe('AWAITING_PAYEE_SELECTION');

    const payeeList = firstTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!payeeList || payeeList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected payee selection block.');
    }

    const secondTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: firstTurn.assistantMessage.messageId,
      sourceBlockId: payeeList.blockId,
      selectedItemId: 'payee_bob_current',
    });

    expect(secondTurn.session.state).toBe('AWAITING_DEBIT_ACCOUNT_SELECTION');

    const accountList = secondTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!accountList || accountList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected debit account selection block.');
    }

    const thirdTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: secondTurn.assistantMessage.messageId,
      sourceBlockId: accountList.blockId,
      selectedItemId: 'acct_primary',
    });

    expect(thirdTurn.session.state).toBe('AWAITING_CONFIRMATION');

    const summaryCard = thirdTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SUMMARY_CARD');

    if (!summaryCard || summaryCard.type !== 'SUMMARY_CARD') {
      throw new Error('Expected domestic payment summary card.');
    }

    const fourthTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'CLICK_ACTION',
      sourceMessageId: thirdTurn.assistantMessage.messageId,
      sourceBlockId: summaryCard.blockId,
      actionValue: 'CONFIRM_PAYMENT',
    });

    expect(fourthTurn.session.state).toBe('COMPLETED');
    expect(fourthTurn.session.status).toBe('COMPLETED');
    expect(fourthTurn.activeDraft?.status).toBe('CONFIRMED');
    expect(fourthTurn.activeDraft?.downstreamReference).toMatch(/^DOM-/);

    const messages = await listChatMessages(user.profileId, sessionId);
    expect(messages.length).toBeGreaterThan(7);
  });

  it('returns registered payee lookup results without creating a payment draft', async () => {
    const user = await profileLogin({
      profileId: 'profile_victor',
      password: 'tb123',
    });
    const created = await createChatSession(user.profileId);

    const turn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'Do I have Sarah registered as a payee?',
    });

    expect(turn.session.state).toBe('IDLE');
    expect(turn.activeDraft).toBeNull();
    expect(turn.assistantMessage.contentBlocks?.some((block) => block.type === 'SUMMARY_CARD')).toBe(true);
  });

  it('keeps the selected payee when the user later supplies only amount or date', async () => {
    const user = await profileLogin({
      profileId: 'profile_victor',
      password: 'tb123',
    });
    const created = await createChatSession(user.profileId);

    const firstTurn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'Pay Bob',
    });
    const payeeList = firstTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!payeeList || payeeList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected payee selection block.');
    }

    await submitUiEvent(user.profileId, created.sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: firstTurn.assistantMessage.messageId,
      sourceBlockId: payeeList.blockId,
      selectedItemId: 'payee_bob_current',
    });

    const amountTurn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: '100 HKD',
    });

    expect(amountTurn.session.state).toBe('COLLECTING_DETAILS');
    expect(amountTurn.activeDraft?.selectedPayee?.payeeId).toBe('payee_bob_current');
    expect(amountTurn.activeDraft?.amount).toBe(100);
    expect(amountTurn.activeDraft?.paymentDate).toBeNull();
    expect(amountTurn.assistantMessage.contentBlocks?.some((block) => block.type === 'SELECTABLE_LIST')).toBe(false);
    expect(amountTurn.assistantMessage.contentBlocks?.[0]?.type).toBe('TEXT');
    if (amountTurn.assistantMessage.contentBlocks?.[0]?.type === 'TEXT') {
      expect(amountTurn.assistantMessage.contentBlocks[0].text).toContain('payment date');
      expect(amountTurn.assistantMessage.contentBlocks[0].text).not.toContain('amount, payment date');
    }

    const dateTurn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'tomorrow',
    });

    expect(dateTurn.session.state).toBe('AWAITING_DEBIT_ACCOUNT_SELECTION');
    expect(dateTurn.activeDraft?.selectedPayee?.payeeId).toBe('payee_bob_current');
    expect(dateTurn.activeDraft?.amount).toBe(100);
    expect(dateTurn.activeDraft?.paymentDate).toBeTruthy();
  });

  it('lists debit accounts when the user asks for my accounts', async () => {
    const user = await profileLogin({
      profileId: 'profile_victor',
      password: 'tb123',
    });
    const created = await createChatSession(user.profileId);

    const turn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'List my accounts',
    });

    expect(turn.session.state).toBe('IDLE');
    expect(turn.assistantMessage.contentBlocks?.some((block) => block.type === 'SUMMARY_CARD')).toBe(true);
  });

  it('clears amount and date when the user changes payee', async () => {
    const user = await profileLogin({
      profileId: 'profile_victor',
      password: 'tb123',
    });
    const created = await createChatSession(user.profileId);

    const firstTurn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'Pay Bob 5000 HKD today',
    });
    const payeeList = firstTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!payeeList || payeeList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected payee selection block.');
    }

    await submitUiEvent(user.profileId, created.sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: firstTurn.assistantMessage.messageId,
      sourceBlockId: payeeList.blockId,
      selectedItemId: 'payee_bob_current',
    });

    const changePayeeTurn = await sendChatMessage(user.profileId, created.sessionId, {
      messageText: 'Pay Sarah 900 HKD tomorrow',
    });

    expect(changePayeeTurn.session.state).toBe('COLLECTING_DETAILS');
    expect(changePayeeTurn.activeDraft?.selectedPayee?.payeeId).toBe('payee_sarah_salary');
    expect(changePayeeTurn.activeDraft?.amount).toBeNull();
    expect(changePayeeTurn.activeDraft?.paymentDate).toBeNull();
    if (changePayeeTurn.assistantMessage.contentBlocks?.[0]?.type === 'TEXT') {
      expect(changePayeeTurn.assistantMessage.contentBlocks[0].text).toContain('amount');
      expect(changePayeeTurn.assistantMessage.contentBlocks[0].text).toContain('payment date');
    }
  });

  it('rejects profile login when the POC access password is incorrect', async () => {
    await expect(
      profileLogin({
        profileId: 'profile_victor',
        password: 'wrong-password',
      }),
    ).rejects.toThrow('Invalid POC access password.');
  });
});
