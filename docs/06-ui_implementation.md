# chat2pay - UI Implementation (High-Fidelity)

## 1. Purpose

This document summarizes the current implemented frontend UI in
`chat2pay-web/` and clarifies how it should be used for the next backend-backed
POC slice.

## 2. Design Direction

The current implementation already matches the intended visual direction:

- enterprise-finance visual language
- HSBC-inspired red / black / white palette
- square corners and explicit borders
- restrained motion
- desktop-first chat workbench layout

This should not be materially redesigned for the next step.

## 3. What Should Stay Stable

- profile selector page layout
- shared password dialog
- left sidebar structure
- chat workspace layout
- message list styling
- summary card / list / form visual language

The next implementation should mainly swap data flow, not page design.

## 4. Profile Selection

Implemented in:

- `chat2pay-web/src/pages/profile-selector/ProfileSelectorPage.tsx`

Current and target behavior:

- fetch profile list from backend
- keep the shared password gate
- current POC may return only one profile
- the most important backend field is `username`, which maps to downstream
  `payment10`
- other display fields can remain lightweight demo data returned by the backend

## 5. Chat Workspace

Implemented in:

- `chat2pay-web/src/pages/chat-workspace/ChatWorkspacePage.tsx`

Current layout should remain:

- left sidebar with New Chat, utility placeholders, history, and profile footer
- right workspace with session header, workflow summary, messages, and composer

## 6. Target Interaction Model for the Next POC Slice

The UI should keep the current structured chat surfaces, but the backend-backed
behavior should be narrowed to the first supported journey:

- domestic payment to a registered payee
- determine payee and amount
- show a confirmation summary
- after user confirmation, backend directly calls confirm payment

Unsupported requests should render a concise info/error style block, not trigger
large UI changes.

## 7. Structured Blocks That Matter Now

The current UI already has the right primitives:

- text blocks for assistant guidance
- selectable lists for payee disambiguation if needed
- simple forms for collecting payee name and amount when free text is missing
- summary cards for final confirmation

These are sufficient for the first real backend integration.

## 7.1 Rule For Future Journeys

If a future journey adds extra backend steps such as limit check, fraud check,
FX quote, or compliance review, the frontend should still stay on the same
conversation surfaces.

Preferred rendering pattern:

- text or info blocks for status and guidance
- selectable lists for ambiguity resolution
- simple forms for missing journey inputs
- summary cards for review and confirmation

Do not add journey-specific frontend orchestration or dedicated stepper pages
just because the backend added more downstream APIs.

## 8. Workflow Visibility

Implemented in:

- `chat2pay-web/src/shared/ui/WorkflowOverview.tsx`

The visual pattern can stay, but the copy and state mapping should be updated to
match the new backend workflow:

- collect payment details
- resolve payee if needed
- await confirmation
- confirm payment
- completed / failed / cancelled

This is a copy/state update, not a layout redesign.

## 9. Mock Flow Status

Current mock behavior in:

- `chat2pay-web/src/shared/api/mockServer.ts`
- `chat2pay-web/src/features/api-mode/ApiModeSwitch.tsx`

Important note:

- the existing mock flow is broader than the next backend POC
- it currently models extra steps such as payment rail choice and proposal-style
  review
- those should not drive the real backend implementation
- the frontend should keep a fast switch between `mock` and real `backend` mode
  for local demos
- the default mode may come from environment, but the user should also be able
  to switch at runtime without redesigning the page

The real backend behavior should follow the backend-owned flow defined in:

- `docs/01-system_design.md`
- `docs/11-backend-skill.md`

The mock flow remains useful for:

- UI-only iteration when the backend is not running
- preserving a broader demo dataset than the current backend slice
- quick comparison between mock behavior and the latest backend wiring

## 10. Source of Truth

For frontend implementation decisions, use this order:

1. `chat2pay-web/` for layout and styling
2. `docs/06-ui_implementation.md` for UX constraints
3. `docs/01-system_design.md` for backend ownership boundaries
4. `docs/11-backend-skill.md` for the first payment journey behavior
5. `docs/02-api_contract.yaml` for FE/BE payload shape
