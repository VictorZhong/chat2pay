import { beforeEach, describe, expect, it } from 'vitest';
import { createChatSession, listChatMessages, profileLogin, resetMockData, sendChatMessage, submitUiEvent } from '@/shared/api/mockServer';

describe('mockServer transfer flow', () => {
  beforeEach(() => {
    resetMockData();
  });

  it('creates a new session and completes a transfer through UI events', async () => {
    const user = await profileLogin('profile_victor');
    const created = await createChatSession(user.profileId);
    const sessionId = created.session.sessionId;

    const firstTurn = await sendChatMessage(user.profileId, sessionId, {
      messageText: 'Pay Tom 5000 HKD',
    });

    expect(firstTurn.workflowState).toBe('RESOLVING_AMBIGUITY');

    const payeeList = firstTurn.assistantMessages[0].contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!payeeList || payeeList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected payee selection block.');
    }

    const secondTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: firstTurn.assistantMessages[0].messageId,
      sourceBlockId: payeeList.blockId,
      selectedItemIds: ['payee_tom_lee'],
    });

    expect(secondTurn.workflowState).toBe('READY_FOR_PAYMENT_OPTIONS');

    const railList = secondTurn.assistantMessages[0].contentBlocks?.find((block) => block.type === 'SELECTABLE_LIST');

    if (!railList || railList.type !== 'SELECTABLE_LIST') {
      throw new Error('Expected payment rail selection block.');
    }

    const thirdTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'SELECT_ITEM',
      sourceMessageId: secondTurn.assistantMessages[0].messageId,
      sourceBlockId: railList.blockId,
      selectedItemIds: ['ORTT'],
    });

    expect(thirdTurn.workflowState).toBe('AWAITING_USER_CONFIRMATION');

    const summaryCard = thirdTurn.assistantMessages[0].contentBlocks?.find((block) => block.type === 'SUMMARY_CARD');

    if (!summaryCard || summaryCard.type !== 'SUMMARY_CARD') {
      throw new Error('Expected proposal summary card.');
    }

    const fourthTurn = await submitUiEvent(user.profileId, sessionId, {
      eventType: 'CLICK_ACTION',
      sourceMessageId: thirdTurn.assistantMessages[0].messageId,
      sourceBlockId: summaryCard.blockId,
      selectedItemIds: ['CONFIRM_TRANSFER'],
    });

    expect(fourthTurn.workflowState).toBe('COMPLETED');
    expect(fourthTurn.session.status).toBe('COMPLETED');

    const messagePage = await listChatMessages(user.profileId, sessionId);
    expect(messagePage.total).toBeGreaterThan(4);
  });
});
