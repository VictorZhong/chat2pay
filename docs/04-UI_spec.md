# chat2pay - UI Specification (Low-Fidelity)

## 1. Overview

This document defines the low-fidelity UI specification for the **chat2pay** POC.

The product is a desktop-oriented internal web application with:
- a landing page for profile selection
- a ChatGPT-inspired main workspace
- a collapsible left sidebar
- a right-side chat area
- assistant support for:
  - plain text
  - structured cards
  - selectable lists
  - simple forms

## 2. Design Intent

### Primary UX Goals
- make the transfer journey feel conversational
- reduce typing with structured UI when ambiguity exists
- keep the page visually simple for internal demo use
- keep navigation obvious: login -> new chat -> chat history -> logout

### Interaction Principles
- free-text input is always available at the bottom of the chat area
- structured responses appear inline in the message stream
- list selections and simple forms should feel like natural extensions of the conversation
- the sidebar footer identity block remains fixed when the sidebar is expanded
- logout returns the user to the profile selection page

## 3. Information Architecture

## 3.1 Landing Page
- app branding: `chat2pay`
- short subtitle / one-line description
- list of 4–5 profile cards
- each profile card contains:
  - avatar
  - display name
  - username
  - market / profile code
- clicking a profile triggers pseudo-login

## 3.2 Main Workspace
### Left Sidebar
- `New Chat` button at the top
- placeholder navigation items:
  - My Account
  - My Payee
  - Transaction History
- chat history section
- bottom fixed user identity area:
  - avatar
  - username
  - display name
  - click => settings popover
- settings popover:
  - Logout

### Right Workspace
- session title / top bar
- message stream
- inline structured response blocks
- bottom text input area

## 4. Screen 1 - Landing Page (Profile Selection)

```text
+--------------------------------------------------------------------------------------+
|                                      chat2pay                                        |
|                          Internal conversational transfer POC                        |
|--------------------------------------------------------------------------------------|
|                                                                                      |
|  Select a profile to continue                                                        |
|                                                                                      |
|  +-----------------------+   +-----------------------+   +-----------------------+   |
|  | (avatar)              |   | (avatar)              |   | (avatar)              |   |
|  | Iris Chen             |   | Tom Lee               |   | Sarah Wong            |   |
|  | iris.chen             |   | tom.lee               |   | sarah.wong            |   |
|  | HK_STAFF_001          |   | HK_RETAIL_001         |   | SG_RETAIL_001         |   |
|  | [ Enter ]             |   | [ Enter ]             |   | [ Enter ]             |   |
|  +-----------------------+   +-----------------------+   +-----------------------+   |
|                                                                                      |
|  +-----------------------+   +-----------------------+                                |
|  | (avatar)              |   | (avatar)              |                                |
|  | Demo User A           |   | Demo User B           |                                |
|  | demo.user.a           |   | demo.user.b           |                                |
|  | POC_A                 |   | POC_B                 |                                |
|  | [ Enter ]             |   | [ Enter ]             |                                |
|  +-----------------------+   +-----------------------+                                |
|                                                                                      |
+--------------------------------------------------------------------------------------+
```

### Component Notes
- Centered layout
- Card-based selection
- Ant Design `Card` + Tailwind spacing utilities
- Single click enters main app

## 5. Screen 2 - Main Workspace (Sidebar Expanded)

```text
+---------------------------------------------------------------------------------------------------------------+
| chat2pay                                                                                     [ Session Title ] |
|---------------------------------------------------------------------------------------------------------------|
| +------------------------------+  +--------------------------------------------------------------------------+ |
| | [ + New Chat ]               |  | Assistant                                                                | |
| |------------------------------|  | Hi Iris, I can help you transfer money, review recent payees, or       | |
| | My Account        (disabled) |  | check transaction status. What would you like to do today?            | |
| | My Payee          (disabled) |  |                                                                          | |
| | Transaction History(disabled)|  | You                                                                      | |
| |------------------------------|  | Pay Tom 5000 HKD                                                        | |
| | Chat History                 |  |                                                                          | |
| | - Transfer to Tom            |  | Assistant                                                                | |
| | - ORTT payment               |  | I found two payees named Tom. Please choose one.                      | |
| | - Follow-up on limit         |  |                                                                          | |
| | - Yesterday draft            |  | +------------------------------------------------------------------+   | |
| |                              |  | | Select payee                                                     |   | |
| |                              |  | | ---------------------------------------------------------------  |   | |
| |                              |  | | ( ) Tom Lee - HSBC HK - xxxx1234                              |   | |
| |                              |  | | ( ) Tom Chan - HSBC HK - xxxx5678                             |   | |
| |                              |  | | [ Submit selection ]                                           |   | |
| |                              |  | +------------------------------------------------------------------+   | |
| |                              |  |                                                                          | |
| |                              |  | [Type your message here...]                               [ Send ]      | |
| |                              |  +--------------------------------------------------------------------------+ |
| |------------------------------|                                                                             |
| | (avatar) Iris Chen           |                                                                             |
| | iris.chen                    |                                                                             |
| +------------------------------+                                                                             |
+---------------------------------------------------------------------------------------------------------------+
```

### Behavior Notes
- Sidebar is fixed width when expanded
- User identity area is pinned to the bottom
- History list scrolls independently if long
- Main chat area is the visual focus

## 6. Screen 3 - Sidebar Collapsed

```text
+---------------------------------------------------------------------------------------------------------------+
| +----+  +--------------------------------------------------------------------------------------------------+ |
| | +  |  | [ Session Title ]                                                                                | |
| |----|  |--------------------------------------------------------------------------------------------------| |
| | A  |  | Chat area                                                                                        | |
| | P  |  |                                                                                                  | |
| | T  |  |                                                                                                  | |
| | H  |  |                                                                                                  | |
| |----|  |                                                                                                  | |
| | :) |  |                                                                                                  | |
| +----+  +--------------------------------------------------------------------------------------------------+ |
+---------------------------------------------------------------------------------------------------------------+
```

### Behavior Notes
- Top action remains `New Chat`
- Placeholder items can collapse to icons
- Bottom avatar remains visible as icon-only
- Clicking avatar opens popover near the footer icon

## 7. Screen 4 - Transfer Summary Card

```text
+----------------------------------------------------------------------------------+
| Assistant                                                                        |
| Please review the transfer summary before confirming.                            |
|                                                                                  |
| +------------------------------------------------------------------------------+ |
| | Transfer summary                                                             | |
| |------------------------------------------------------------------------------| |
| | From account        My Savings Account - xxxx8891                            | |
| | Payee               Tom Lee - xxxx1234                                       | |
| | Amount              5,000.00 HKD                                             | |
| | Payment rail        ORTT                                                     | |
| | Fee                 120.00 HKD                                               | |
| | Estimated arrival   Today before 18:00                                       | |
| | Proposal ID         PROP-20260416-001                                        | |
| +------------------------------------------------------------------------------+ |
|                                                                                  |
| [ Confirm ]   [ Cancel ]                                                         |
+----------------------------------------------------------------------------------+
```

### Behavior Notes
- Inline within the assistant message stream
- Can be backed by a `SUMMARY_CARD` block
- Buttons may call the structured UI event endpoint
- User may still type `confirm` instead of clicking

## 8. Screen 5 - Simple Form Block

```text
+----------------------------------------------------------------------------------+
| Assistant                                                                        |
| I still need a few details to continue.                                          |
|                                                                                  |
| +------------------------------------------------------------------------------+ |
| | Payment details                                                               | |
| |------------------------------------------------------------------------------| |
| | Amount            [________________________]                                  | |
| | Currency          [ HKD v ]                                                   | |
| | Note              [________________________]                                  | |
| |                                                                              | |
| |                              [ Submit ]                                      | |
| +------------------------------------------------------------------------------+ |
+----------------------------------------------------------------------------------+
```

### Behavior Notes
- Used only when structured entry is easier than repeated clarification
- Fields should be limited and simple
- The form submits through the UI event endpoint
- Client may optionally mirror a canonical user message after submission

## 9. Screen 6 - Settings Popover

```text
+----------------------------------+
| (avatar) Iris Chen               |
| iris.chen                        |
|----------------------------------|
| Logout                           |
+----------------------------------+
```

### Behavior Notes
- Triggered from the bottom-left user identity area
- POC scope: only one action
- Logout navigates to landing page and clears the active profile context

## 10. Screen 7 - History Viewing

```text
+---------------------------------------------------------------------------------------------------------------+
| Sidebar                                         | Existing Session: Transfer to Tom                           |
|------------------------------------------------|--------------------------------------------------------------|
| - New Chat                                      | Assistant                                                    |
| - My Account                                    | Hi Iris, I can help with transfers.                          |
| - My Payee                                      |                                                              |
| - Transaction History                           | You                                                          |
| - Transfer to Tom     <-- selected              | Pay Tom 5000 HKD                                             |
| - ORTT payment                                  |                                                              |
| - Yesterday draft                               | Assistant                                                    |
|                                                 | I found two payees named Tom. Please choose one.            |
|                                                 |                                                              |
|                                                 | ...full historical thread...                                 |
|                                                 |                                                              |
|                                                 | [ input enabled if ACTIVE ]                                  |
+---------------------------------------------------------------------------------------------------------------+
```

### Behavior Notes
- Sidebar selection loads one session
- If session status is `ACTIVE`, input remains enabled
- If session status is `COMPLETED` or `CANCELLED`, the message stream becomes read-only

## 11. Component Inventory

| Component | Purpose |
|---|---|
| `ProfileCard` | Select a POC identity |
| `SidebarNavItem` | Placeholder menu item |
| `ChatHistoryItem` | Session list item |
| `UserIdentityFooter` | Fixed avatar + username block |
| `SettingsPopover` | Logout only |
| `ChatBubble` | Plain text message |
| `SummaryCard` | Transfer summary, proposal result, success result |
| `SelectableListCard` | Candidate account / payee / rail selection |
| `SimpleFormCard` | Small structured data capture |
| `InputComposer` | Free-text input and send |
| `SessionHeader` | Title, status, optional state badge |

## 12. Suggested Visual Styling

### Layout
- Use Ant Design layout primitives
- Use Tailwind for spacing, sizing, and fine-grained layout control
- Desktop-first width and height assumptions for internal demo

### Typography
- Clean, understated
- Avoid dense tables in the main chat stream
- Structured cards should be visually separate from text bubbles

### Interaction
- Hover feedback on sidebar items and history rows
- Clear selected state in chat history
- Distinct but subtle status chip for active/completed/cancelled sessions

## 13. UX Notes for the POC

- Keep the number of simultaneous structured blocks small
- Prefer one assistant task per turn
- Use selectable lists whenever ambiguity exists
- Use summary cards for irreversible steps such as confirm
- Preserve chat history readability even when structured blocks are present
- Do not hide the free-text input unless the page is in a blocked loading state

## 14. Future UI Extensions

The design allows future addition of:
- right-side draft summary panel
- recent action shortcuts
- richer profile settings
- dark mode
- keyboard shortcuts
- utility pages behind the placeholder sidebar items
- transfer success receipt view
