import { useEffect, useState } from 'react';
import type { ChatSessionStatus, ConversationState } from '@/shared/api/contracts';
import { CheckIcon, CloseIcon, RightIcon } from '@/shared/ui/icons';

type Stage = {
  key: string;
  title: string;
  description: string;
  states: ConversationState[];
};

const STAGES: Stage[] = [
  {
    key: 'collect',
    title: 'Capture request',
    description: 'Gather the payee, amount, and payment date from the conversation.',
    states: ['IDLE', 'COLLECTING_DETAILS'],
  },
  {
    key: 'select',
    title: 'Resolve payee',
    description: 'Choose the correct registered payee when more than one match exists.',
    states: ['AWAITING_PAYEE_SELECTION'],
  },
  {
    key: 'confirm',
    title: 'Await confirmation',
    description: 'Show the domestic payment summary and wait for an explicit user confirmation.',
    states: ['AWAITING_CONFIRMATION'],
  },
  {
    key: 'execute',
    title: 'Submit payment',
    description: 'Call the backend-owned payment confirmation path.',
    states: ['EXECUTING'],
  },
  {
    key: 'outcome',
    title: 'Outcome',
    description: 'Display success, failure, or cancellation.',
    states: ['COMPLETED', 'FAILED', 'CANCELLED'],
  },
];

function stageIndexForState(state: ConversationState) {
  const index = STAGES.findIndex((stage) => stage.states.includes(state));
  return index >= 0 ? index : 0;
}

function currentStageForState(state: ConversationState, sessionStatus: ChatSessionStatus) {
  if (sessionStatus === 'CANCELLED' || state === 'CANCELLED') {
    return {
      title: 'Draft cancelled',
      subtitle: 'Conversation closed',
    };
  }

  if (sessionStatus === 'FAILED' || state === 'FAILED') {
    return {
      title: 'Execution failed',
      subtitle: 'Review the latest backend message',
    };
  }

  if (sessionStatus === 'COMPLETED' || state === 'COMPLETED') {
    return {
      title: 'Payment completed',
      subtitle: 'Reference returned',
    };
  }

  const stage = STAGES[stageIndexForState(state)] ?? STAGES[0];
  return {
    title: stage.title,
    subtitle: stage.description,
  };
}

function headerCopy(sessionStatus: ChatSessionStatus) {
  if (sessionStatus === 'COMPLETED') {
    return 'Completed';
  }

  if (sessionStatus === 'FAILED') {
    return 'Failed';
  }

  if (sessionStatus === 'CANCELLED') {
    return 'Cancelled';
  }

  if (sessionStatus === 'ARCHIVED') {
    return 'Archived';
  }

  return 'Active';
}

export function WorkflowOverview({
  state,
  sessionStatus,
}: {
  state: ConversationState;
  sessionStatus: ChatSessionStatus;
}) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }

    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('keydown', handleEscape);
    };
  }, [open]);

  const currentIndex = stageIndexForState(state);
  const currentStage = currentStageForState(state, sessionStatus);
  const currentHeader = headerCopy(sessionStatus);

  return (
    <>
      <button className="brand-workflow-trigger" onClick={() => setOpen(true)}>
        <span className="min-w-0 flex-1 truncate text-sm font-semibold text-brand-black">
          {currentStage.title}
        </span>
        <span className="brand-workflow-count">
          <span>
            {Math.max(currentIndex + 1, 1)} / {STAGES.length}
          </span>
          <RightIcon className="h-3 w-3" />
        </span>
      </button>

      {open ? (
        <div className="brand-dialog-backdrop" onClick={() => setOpen(false)}>
          <div className="brand-dialog-panel" onClick={(event) => event.stopPropagation()}>
            <div className="border-b border-brand-line px-6 pb-4 pt-5">
              <div className="pr-10">
                <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">
                  Conversation status
                </p>
                <h3 className="mt-2 text-lg font-semibold text-brand-black">{currentStage.title}</h3>
                <p className="mt-2 text-sm leading-6 text-brand-gray">{currentStage.subtitle}</p>
              </div>
              <button
                className="absolute right-5 top-5 flex h-9 w-9 items-center justify-center border border-brand-line bg-white text-brand-black transition hover:border-brand-red hover:text-brand-red"
                onClick={() => setOpen(false)}
                aria-label="Close status dialog"
              >
                <CloseIcon className="h-4 w-4" />
              </button>
            </div>

            <div className="space-y-4 px-6 pb-6 pt-4">
              <div className="flex flex-wrap items-center gap-3 border-b border-brand-line pb-4">
                <span className="brand-chip border-brand-black text-brand-black">{currentHeader}</span>
                <p className="text-xs uppercase tracking-[0.14em] text-brand-gray">
                  Step {Math.max(currentIndex + 1, 1)} of {STAGES.length}
                </p>
              </div>

              <div className="space-y-2">
                {STAGES.map((stage, index) => {
                  const isCurrent = index === currentIndex;
                  const isCompleted =
                    currentIndex > index ||
                    state === 'COMPLETED' ||
                    state === 'FAILED' ||
                    state === 'CANCELLED';
                  const isUpcoming = index > currentIndex;

                  return (
                    <div
                      key={stage.key}
                      className={[
                        'grid gap-3 border px-4 py-3 md:grid-cols-[40px_1fr_auto]',
                        isCurrent && 'border-brand-red bg-[#fff4f5]',
                        isCompleted &&
                          !isCurrent &&
                          'border-brand-black bg-[linear-gradient(180deg,#ffffff_0%,#f7f7f7_100%)]',
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
                        {isCompleted && !isCurrent ? <CheckIcon className="h-4 w-4" /> : index + 1}
                      </div>

                      <div>
                        <div className="flex flex-wrap items-center gap-3">
                          <h4 className="text-sm font-semibold text-brand-black">{stage.title}</h4>
                          {isCurrent ? (
                            <span className="brand-chip border-brand-red text-brand-red">Current</span>
                          ) : null}
                        </div>
                        <p className="mt-1 text-xs leading-6 text-brand-gray">{stage.description}</p>
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
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
