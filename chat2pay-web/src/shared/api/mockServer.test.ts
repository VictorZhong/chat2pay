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

    expect(secondTurn.session.state).toBe('AWAITING_CONFIRMATION');

    const summaryCard = secondTurn.assistantMessage.contentBlocks?.find((block) => block.type === 'SUMMARY_CARD');

    if (!summaryCard || summaryCard.type !== 'SUMMARY_CARD') {
      throw new Error('Expected domestic payment summary card.');
    }

    const thirdTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'CLICK_ACTION',
      sourceMessageId: secondTurn.assistantMessage.messageId,
      sourceBlockId: summaryCard.blockId,
      actionValue: 'CONFIRM_PAYMENT',
    });

    expect(thirdTurn.session.state).toBe('COMPLETED');
    expect(thirdTurn.session.status).toBe('COMPLETED');
    expect(thirdTurn.activeDraft?.status).toBe('CONFIRMED');
    expect(thirdTurn.activeDraft?.downstreamReference).toMatch(/^DOM-/);

    const messages = await listChatMessages(user.profileId, sessionId);
    expect(messages.length).toBeGreaterThan(5);
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

  it('rejects profile login when the profile password is incorrect', async () => {
    await expect(
      profileLogin({
        profileId: 'profile_victor',
        password: 'wrong-password',
      }),
    ).rejects.toThrow('Incorrect password.');
  });
});
