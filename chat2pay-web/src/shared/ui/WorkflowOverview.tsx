import { ApartmentOutlined, CheckOutlined, CloseOutlined, InfoCircleOutlined, RightOutlined } from '@ant-design/icons';
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
      description: 'The session is complete. The flow below shows the full path that was executed.',
    };
  }

  if (sessionStatus === 'CANCELLED') {
    return {
      title: 'Execution cancelled',
      description: 'The transfer was cancelled before completion. Review the flow to see where the session stopped.',
    };
  }

  if (sessionStatus === 'ARCHIVED') {
    return {
      title: 'Session archived',
      description: 'This conversation has been archived. The flow remains available for review.',
    };
  }

  return {
    title: 'Execution in progress',
    description: 'The workflow is still active. The highlighted stage shows the current position of the transfer.',
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
        <span className="brand-workflow-icon">
          <ApartmentOutlined />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[11px] font-semibold uppercase tracking-[0.16em] text-brand-gray">
            Current Flow Step
          </span>
          <span className="mt-1 block truncate text-sm font-semibold text-brand-black">{currentStage.title}</span>
          <span className="mt-1 block text-xs uppercase tracking-[0.12em] text-brand-gray">
            {'subtitle' in currentStage
              ? currentStage.subtitle
              : `Step ${Math.max(currentIndex + 1, 1)} of ${FLOW_STAGES.length} • View Full Flow`}
          </span>
        </span>
        <RightOutlined className="text-brand-gray" />
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
            <h3 className="mt-2 text-xl font-semibold text-brand-black">{currentStage.title}</h3>
          </div>
        }
      >
        <div className="space-y-6">
          <div className="border border-brand-black bg-[linear-gradient(180deg,#ffffff_0%,#f7f7f7_100%)] p-5">
            <div className="mb-3 flex items-center gap-3">
              <InfoCircleOutlined className="text-brand-red" />
              <p className="text-sm font-semibold text-brand-black">{terminalCopy.title}</p>
            </div>
            <p className="text-sm leading-7 text-brand-gray">{terminalCopy.description}</p>
          </div>

          <div className="space-y-3">
            {FLOW_STAGES.map((stage, index) => {
              const isCurrent = index === currentIndex;
              const isCompleted = currentIndex > index || workflowState === 'COMPLETED';
              const isUpcoming = index > currentIndex;

              return (
                <div
                  key={stage.key}
                  className={[
                    'grid gap-4 border p-5 md:grid-cols-[56px_1fr_auto]',
                    isCurrent && 'border-brand-red bg-[#fff4f5]',
                    isCompleted && !isCurrent && 'border-brand-black bg-[linear-gradient(180deg,#ffffff_0%,#f7f7f7_100%)]',
                    isUpcoming && 'border-brand-line bg-white',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                >
                  <div
                    className={[
                      'flex h-14 w-14 items-center justify-center border text-sm font-semibold',
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
                      <h4 className="text-base font-semibold text-brand-black">{stage.title}</h4>
                      {isCurrent ? (
                        <span className="brand-chip border-brand-red text-brand-red">Current</span>
                      ) : null}
                    </div>
                    <p className="mt-2 text-sm leading-7 text-brand-gray">{stage.description}</p>
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
            <div className="border border-red-700 bg-red-50 p-5">
              <div className="mb-3 flex items-center gap-3">
                <CloseOutlined className="text-red-700" />
                <p className="text-sm font-semibold text-red-800">
                  {sessionStatus === 'CANCELLED' ? 'Flow ended by user' : 'Flow ended with a failure'}
                </p>
              </div>
              <p className="text-sm leading-7 text-red-800">
                This session did not reach the final transfer completion step. The timeline above shows the expected
                control path for the transfer journey.
              </p>
            </div>
          )}
        </div>
      </Modal>
    </>
  );
}
