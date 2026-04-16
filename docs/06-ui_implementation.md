# chat2pay - UI Implementation (High-Fidelity)

## 1. Purpose

This document summarizes the **current implemented frontend UI** in
`chat2pay-web/`.

It replaces the old low-fidelity screen sketch as the practical UI reference for
the POC.

## 2. Design Direction

The current implementation follows a branded enterprise-finance visual language:

- HSBC-inspired red / black / white palette
- square corners and explicit borders
- restrained motion and short, purposeful transitions
- desktop-first workspace with responsive fallback for smaller widths
- conversational UI with structured, workflow-aware control surfaces

The product is intentionally not styled like a consumer chat app.
It behaves more like an internal transfer workbench with conversational guidance.

## 3. Current Frontend Stack

- Vite
- React + TypeScript
- React Router
- TanStack Query
- Zustand
- Tailwind CSS
- custom UI primitives and icons
- mock data flow aligned with the OpenAPI contract

## 4. Implemented Screens

### 4.1 Profile Selection

Implemented in:
- `chat2pay-web/src/pages/profile-selector/ProfileSelectorPage.tsx`

Current behavior:
- shows predefined mock profiles
- branded card layout
- clicking `Enter` opens a shared-password dialog before access is granted
- loading state uses the same branded message-panel language as the chat area

### 4.2 Chat Workspace

Implemented in:
- `chat2pay-web/src/pages/chat-workspace/ChatWorkspacePage.tsx`

Current layout:
- left sidebar with:
  - New Chat
  - placeholder utility items with icons
  - chat history
  - distinct dark profile footer
- right workspace with:
  - session title
  - session status badge
  - current workflow trigger
  - message stream
  - compact input composer

## 5. Message Rendering

Implemented in:
- `chat2pay-web/src/features/message-renderer/`
- `chat2pay-web/src/features/ui-events/StructuredBlocks.tsx`

Supported message forms:
- plain text user messages
- assistant text/info/error blocks
- summary cards
- selectable lists
- simple forms

Behavior notes:
- structured blocks render inline in the conversation
- user actions can come from free text or structured controls
- pending user actions appear in-stream during delayed processing
- assistant loading appears as a branded inline processing panel

## 6. Workflow Visibility

Implemented in:
- `chat2pay-web/src/shared/ui/WorkflowOverview.tsx`

Current behavior:
- top-bar trigger shows current step name plus `x / 7`
- clicking opens a compact workflow dialog
- the dialog highlights the current stage and shows progression across the
  transfer journey
- completed, cancelled, and failed outcomes are visually differentiated

## 7. Motion and Interaction

The current UI includes lightweight motion in a controlled way:

- message entry fade / rise
- sidebar width transition when collapsing
- session history hover and selected-state transitions
- sidebar placeholder menu hover transitions
- dialog fade / rise
- branded loading bar animation

Motion is intentionally restrained and should remain low-noise.

Accessibility note:
- reduced-motion users should not be forced through heavy animation

## 8. Mock Data and Flow Coverage

Implemented in:
- `chat2pay-web/src/shared/api/mockServer.ts`

Current mocked flow supports:
- create session
- welcome state
- transfer instruction intake
- payee ambiguity resolution
- payment rail selection
- proposal preparation
- confirmation
- completed / cancelled terminal states

The UI is therefore already high-fidelity enough to act as the practical demo
surface for the POC, even before backend integration is complete.

## 9. Current Source of Truth

For UI decisions, use this precedence:

1. `chat2pay-web/` implementation
2. `docs/06-ui_implementation.md`
3. `docs/01-system_design.md`
4. `docs/02-api_contract.yaml`
