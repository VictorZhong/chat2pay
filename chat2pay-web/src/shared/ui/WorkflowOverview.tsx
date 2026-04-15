import { CheckOutlined, CloseOutlined, RightOutlined } from '@ant-design/icons';
import { Modal } from 'antd';
import { useState } from 'react';
import type { ChatSessionStatus, WorkflowState } from '@/shared/api/contracts';

type FlowStage = {
  key: string;
  title: string;
  description: string;
  states: WorkflowState[];
};

const FLOW_STAGES: FlowStage[] = [
  {
    key: 'collect',
    title: 'Collect transfer details',
    description: 'Gather the payee, amount, currency, and core payment instruction.',
    states: ['IDLE', 'COLLECTING_TRANSFER_INFO'],
  },
  {
    key: 'ambiguity',
    title: 'Resolve ambiguity',
    description: 'Clarify duplicate payees, accounts, or any conflicting transfer context.',
    states: ['RESOLVING_AMBIGUITY'],
  },
  {
    key: 'payment',
    title: 'Select payment option',
    description: 'Choose the most suitable rail before the backend proceeds with control checks.',
    states: ['READY_FOR_PAYMENT_OPTIONS'],
  },
  {
    key: 'validation',
    title: 'Validate and prepare proposal',
    description: 'Run limit checks and prepare the proposal package for user review.',
    states: ['READY_FOR_LIMIT_CHECK', 'READY_FOR_PROPOSE'],
  },
  {
    key: 'review',
    title: 'Await user confirmation',
    description: 'Present the transfer summary and wait for an explicit user decision.',
    states: ['AWAITING_USER_CONFIRMATION'],
  },
  {
    key: 'confirm',
    title: 'Confirm and post transfer',
    description: 'Submit the final confirmation and complete downstream execution.',
    states: ['READY_FOR_CONFIRM'],
  },
  {
    key: 'completed',
    title: 'Transfer completed',
    description: 'Return the reference and switch the session to a read-only outcome state.',
    states: ['COMPLETED'],
  },
];

function stageIndexForState(workflowState: WorkflowState) {
  if (workflowState === 'CANCELLED') {
    return 4;
  }

  if (workflowState === 'FAILED') {
    return 3;
  }

  return FLOW_STAGES.findIndex((stage) => stage.states.includes(workflowState));
}

function currentStageForState(workflowState: WorkflowState, sessionStatus: ChatSessionStatus) {
  if (workflowState === 'CANCELLED' || sessionStatus === 'CANCELLED') {
    return {
      title: 'Transfer cancelled',
      subtitle: 'Session closed • View full flow',
    };
  }

  if (workflowState === 'FAILED') {
    return {
      title: 'Flow interrupted',
      subtitle: 'Review control path • View full flow',
    };
  }

  return FLOW_STAGES[stageIndexForState(workflowState)] ?? FLOW_STAGES[0];
}

function statusCopy(sessionStatus: ChatSessionStatus) {
  if (sessionStatus === 'COMPLETED') {
    return {
      title: 'Execution completed',
    };
  }

  if (sessionStatus === 'CANCELLED') {
    return {
      title: 'Execution cancelled',
    };
  }

  if (sessionStatus === 'ARCHIVED') {
    return {
      title: 'Session archived',
    };
  }

  return {
    title: 'Execution in progress',
  };
}

export function WorkflowOverview({
  workflowState,
  sessionStatus,
}: {
  workflowState: WorkflowState;
  sessionStatus: ChatSessionStatus;
}) {
  const [open, setOpen] = useState(false);

  const currentIndex = stageIndexForState(workflowState);
  const currentStage = currentStageForState(workflowState, sessionStatus);
  const terminalCopy = statusCopy(sessionStatus);

  return (
    <>
      <button className="brand-workflow-trigger" onClick={() => setOpen(true)}>
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-brand-black">
          {currentStage.title}
        </span>
        <span className="brand-workflow-count">
          <span>
            {Math.max(currentIndex + 1, 1)} / {FLOW_STAGES.length}
          </span>
          <RightOutlined />
        </span>
      </button>

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={760}
        rootClassName="brand-flow-modal"
        title={
          <div className="pr-10">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">Transfer Flow</p>
            <h3 className="mt-2 text-lg font-semibold text-brand-black">{currentStage.title}</h3>
          </div>
        }
      >
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-3 border-b border-brand-line pb-4">
            <span className="brand-chip border-brand-black text-brand-black">{terminalCopy.title}</span>
            <p className="text-xs uppercase tracking-[0.14em] text-brand-gray">
              Step {Math.max(currentIndex + 1, 1)} of {FLOW_STAGES.length}
            </p>
          </div>

          <div className="space-y-2">
            {FLOW_STAGES.map((stage, index) => {
              const isCurrent = index === currentIndex;
              const isCompleted = currentIndex > index || workflowState === 'COMPLETED';
              const isUpcoming = index > currentIndex;

              return (
                <div
                  key={stage.key}
                  className={[
                    'grid gap-3 border px-4 py-3 md:grid-cols-[40px_1fr_auto]',
                    isCurrent && 'border-brand-red bg-[#fff4f5]',
                    isCompleted && !isCurrent && 'border-brand-black bg-[linear-gradient(180deg,#ffffff_0%,#f7f7f7_100%)]',
                    isUpcoming && 'border-brand-line bg-white',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                >
                  <div
                    className={[
                      'flex h-10 w-10 items-center justify-center border text-sm font-semibold',
                      isCurrent && 'border-brand-red bg-brand-red text-white',
                      isCompleted && !isCurrent && 'border-brand-black bg-brand-black text-white',
                      isUpcoming && 'border-brand-line bg-brand-fog text-brand-gray',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                  >
                    {isCompleted && !isCurrent ? <CheckOutlined /> : isUpcoming ? index + 1 : index + 1}
                  </div>

                  <div>
                    <div className="flex flex-wrap items-center gap-3">
                      <h4 className="text-sm font-semibold text-brand-black">{stage.title}</h4>
                      {isCurrent ? (
                        <span className="brand-chip border-brand-red text-brand-red">Current</span>
                      ) : null}
                    </div>
                    {isCurrent ? (
                      <p className="mt-1 text-xs leading-6 text-brand-gray">{stage.description}</p>
                    ) : null}
                  </div>

                  <div className="flex items-start justify-end">
                    {isCurrent ? (
                      <span className="brand-status-badge border-brand-red bg-[#fff4f5] text-brand-red">
                        <span className="brand-status-indicator bg-brand-red text-brand-red" />
                        Live
                      </span>
                    ) : isCompleted ? (
                      <span className="brand-status-badge border-brand-black bg-brand-black text-white">
                        <span className="brand-status-indicator bg-brand-red text-brand-red" />
                        Done
                      </span>
                    ) : (
                      <span className="brand-status-badge border-brand-line bg-white text-brand-gray">
                        <span className="brand-status-indicator bg-brand-gray text-brand-gray" />
                        Pending
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {(sessionStatus === 'CANCELLED' || workflowState === 'FAILED') && (
            <div className="border border-red-700 bg-red-50 px-4 py-3">
              <div className="mb-2 flex items-center gap-3">
                <CloseOutlined className="text-red-700" />
                <p className="text-sm font-semibold text-red-800">
                  {sessionStatus === 'CANCELLED' ? 'Flow ended by user' : 'Flow ended with a failure'}
                </p>
              </div>
              <p className="text-xs leading-6 text-red-800">
                The transfer did not reach the final completion step. Use the timeline above to review where it stopped.
              </p>
            </div>
          )}
        </div>
      </Modal>
    </>
  );
}
