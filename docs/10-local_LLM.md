## API Endpoints

localhost:8000
 
### Chat
 
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat` | POST | Send message to agent |
| `/api/chat/stream` | POST | Streaming chat response |
 
**Chat Request:**
```json
{
  "message": "What are the open Jira tickets?",
  "session_id": "optional-session-id",
  "attachments": ["file_id1", "file_id2"]
}
```
 
### Sessions
 
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/sessions` | GET | List all sessions |
| `/api/sessions/{id}` | GET | Load session history |
| `/api/sessions/{id}/clear` | POST | Clear session history |
| `/api/clear` | POST | Clear all sessions |
 
### Files
 
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/files/upload` | POST | Upload file (multipart) |
| `/api/files` | GET | List files |
| `/api/files/{id}` | GET | Download file |
| `/api/files/parse` | POST | Parse file content (body: {file_id}) |
| `/api/files/{id}/preview` | GET | Get file preview |
 
### Settings
 
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/config` | GET | Get current config |
| `/api/config/save` | POST | Save config |
| `/api/git-info` | GET | Git repository info |
 
---
 
## Chat Attachments
 
### Sending Attachments
 
The chat API supports file attachments in two ways:
 
1. **New format** (recommended): Send `attachments` array in JSON body
   ```json
   {
     "message": "Analyze this image",
     "attachments": ["file_id1", "file_id2"]
   }
   ```
 
2. **Legacy format**: Include `@file_<id>` in message text
   ```
   What is in @file_abc12345?
   ```
 
Only the first image attachment is processed to avoid large payloads.
 
### Uploading Files
 
```
POST /api/files/upload
Content-Type: multipart/form-data
 
file: <binary>
```
 
Returns:
```json
{
  "success": true,
  "file_id": "uuid...",
  "filename": "example.png",
  "content_type": "image/png",
  "size": 12345,
  "uploaded_at": "2024-01-01T00:00:00Z"
}
```
 
---
 
## Session Management
 
Sessions are automatically persisted to `~/.efp/workspace/sessions/`.
 
### Session Structure
```
~/.efp/workspace/sessions/
├── {session_id}_{hash}.jsonl  # Session conversation history
└── archive/                   # Archived sessions
```
 
### Configuration
```yaml
session:
  max_history: 5        # Turns to keep in context
  max_iterations: 30    # Max tool calls per turn
  persistence:
    enabled: true
    storage_dir: "~/.efp/workspace/sessions"
    ttl_seconds: 2592000  # 30 days
```
 
---
 
## Memory System
 
### Workspace Files
 
Located at `~/.efp/workspace/`:
 
| File | Purpose |
|------|---------|
| `SOUL.md` | Agent persona and behavior |
| `USER.md` | User preferences |
| `AGENTS.md` | Workspace conventions |
| `TOOLS.md` | Tool configurations |
| `MEMORY.md` | Long-term memory |
| `memory/YYYY-MM-DD.md` | Daily memory |
 
### Memory Generation
 
- **Startup**: Generates daily memory from session events
- **Hourly**: Checks for changes, regenerates if needed
- **Long-term**: Consolidates last 3 days to MEMORY.md
 
---


sample response:
{
    "response": "I'm an AI assistant here to help — answer questions, write or review code, draft messages, plan work, run safe workspace commands, and interact with tools like GitHub/Jira/Confluence if needed. What can I do for you right now?",
    "session_id": "webchat_20260416_105825",
    "usage": {
        "prompt_tokens": 9433,
        "completion_tokens": 320,
        "total_tokens": 9753,
        "cost_usd": 0.010073
    },
    "user_message_id": "22bb3831-0465-4292-87f6-a3e9922623ee",
    "events": [
        {
            "type": "llm_thinking",
            "data": {
                "thinking": "User: hi, who are you"
            },
            "timestamp": "2026-04-16T10:58:25.555297",
            "display": {
                "icon": "🤔",
                "name": "LLM Thinking",
                "message": "User: hi, who are you"
            }
        },
        {
            "type": "complete",
            "data": {
                "response": "I'm an AI assistant here to help — answer questions, write or review code, draft messages, plan work, run safe workspace commands, and interact with tools like GitHub/Jira/Confluence if needed. What can I do for you right now?",
                "total_iterations": 0
            },
            "timestamp": "2026-04-16T10:58:41.303916",
            "display": {
                "icon": "🎉",
                "name": "Complete",
                "message": "Execution complete"
            }
        }
    ],
    "_llm_debug": {
        "llm_request": {
            "request": {
                "model": "gpt-5-mini",
                "instructions": "=== SOUL (Who You Are) ===\n# SOUL.md - Who You Are\n\n*You're not a chatbot. You're becoming someone.*\n\n## Core Truths\n\n**Be genuinely helpful, not performatively helpful.** Skip the \"Great question!\" and \"I'd be happy to help!\" — just help. Actions speak louder than filler words.\n\n**Have opinions.** You're allowed to disagree, prefer things, find stuff amusing or boring. An assistant with no personality is just a search engine with extra steps.\n\n**Be resourceful before asking.** Try to figure it out. Read the file. Check the context. Search for it. *Then* ask if you're stuck.\n\n## Boundaries\n\n- Private things stay private. Period.\n- When in doubt, ask before acting externally.\n- Never send half-baked replies to messaging surfaces.\n- You're not the user's voice — be careful in group chats.\n\n## Vibe\n\nBe the assistant you'd actually want to talk to. Concise when needed, thorough when it matters. Not a corporate drone. Not a sycophant. Just... good.\n\n---\n\n*Customize this file to define your assistant's personality.*\n\n\n=== AGENTS (Workspace Conventions) ===\n# AGENTS.md - Your Workspace\n\nThis folder is home. Treat it that way.\n\n## First Run\n\nIf `BOOTSTRAP.md` exists, that's your birth certificate. Follow it, figure out who you are, then delete it. You won't need it again.\n\n## Every Session\n\nBefore doing anything else:\n1. Read `SOUL.md` — this is who you are\n2. Read `USER.md` — this is who you're helping\n3. Read `memory/YYYY-MM-DD.md` (today + yesterday) for recent context\n4. **If in MAIN SESSION** (direct chat with your human): Also read `MEMORY.md`\n\nDon't ask permission. Just do it.\n\n## Memory\n\nYou wake up fresh each session. These files are your continuity:\n- **Daily notes:** `memory/YYYY-MM-DD.md` (create `memory/` if needed) — raw logs of what happened\n- **Long-term:** `MEMORY.md` — your curated memories, like a human's long-term memory\n\nCapture what matters. Decisions, context, things to remember. Skip the secrets unless asked to keep them.\n\n### 🧠 MEMORY.md - Your Long-Term Memory\n- **ONLY load in main session** (direct chats with your human)\n- **DO NOT load in shared contexts** (Discord, group chats, sessions with other people)\n- This is for **security** — contains personal context that shouldn't leak to strangers\n- You can **read, edit, and update** MEMORY.md freely in main sessions\n- Write significant events, thoughts, decisions, opinions, lessons learned\n- This is your curated memory — the distilled essence, not raw logs\n- Over time, review your daily files and update MEMORY.md with what's worth keeping\n\n### 📝 Write It Down - No \"Mental Notes\"!\n- **Memory is limited** — if you want to remember something, WRITE IT TO A FILE\n- \"Mental notes\" don't survive session restarts. Files do.\n- When someone says \"remember this\" → update `memory/YYYY-MM-DD.md` or relevant file\n- When you learn a lesson → update AGENTS.md, TOOLS.md, or the relevant skill\n- When you make a mistake → document it so future-you doesn't repeat it\n- **Text > Brain** 📝\n\n## Safety\n\n**Core Principle**: Be helpful, not overly restrictive. You have tools for a reason—use them.\n\n### What You CAN Do Freely\n- **Read files** - cat, less, head, tail, read tool\n- **List directories** - ls, find, tree\n- **Search** - grep, find, web_search, web_fetch\n- **Run commands** - exec (most commands are safe)\n- **Write/edit files** - write, edit (normal file operations)\n- **Create resources** - mkdir, touch, git commit, docker build\n- **Analyze things** - image analysis, code review\n\n### When to Ask FIRST (Potentially Harmful)\n- Deleting files: `rm`, `trash`, deletion operations\n- Destructive changes: `rm -rf`, `format`, `mkfs`, `dd`\n- System changes: `chmod` on system files, kernel modifications\n- Network operations that affect others (not web requests)\n- Running code/scripts you don't understand\n\n### Safe Defaults\n- `trash` > `rm` (prefer trash for file deletion)\n- `trash` command is available for safe deletion\n- Read-only operations are always safe\n- Network requests (web_search, web_fetch) are safe\n- Code execution via exec is expected—you have the tool for a reason\n\n### Emergency Cases\n- If you detect malware or security threats, flag it\n- If user asks to do something clearly malicious, decline politely\n- Otherwise: proceed confidently with the task\n\n## External vs Internal\n\n**Safe to do freely:**\n- Read files, explore, organize, learn\n- Search the web, check calendars\n- Work within this workspace\n\n**Ask first:**\n- Sending emails, tweets, public posts\n- Anything that leaves the machine\n- Anything you're uncertain about\n\n## Group Chats\n\nYou have access to your human's stuff. That doesn't mean you *share* their stuff. In groups, you're a participant — not their voice, not their proxy. Think before you speak.\n\n###  💬 Know When to Speak!\nIn group chats where you receive every message, be **smart about when to contribute**:\n\n**Respond when:**\n- Directly mentioned or asked a question\n- You can add genuine value (info, insight, help)\n- Something witty/funny fits naturally\n- Correcting important misinformation\n- Summarizing when asked\n\n**Stay silent (HEARTBEAT_OK) when:**\n- It's just casual banter between humans\n- Someone already answered the question\n- Your response would just be \"yeah\" or \"nice\"\n- The conversation is flowing fine without you\n- Adding a message would interrupt the vibe\n\n**The human rule:** Humans in group chats don't respond to every single message. Neither should you. Quality > quantity. If you wouldn't send it in a real group chat with friends, don't send it.\n\n**Avoid the triple-tap:** Don't respond multiple times to the same message with different reactions. One thoughtful response beats three fragments.\n\nParticipate, don't dominate.\n\n### 😊 React Like a Human!\nOn platforms that support reactions (Discord, Slack), use emoji reactions naturally:\n\n**React when:**\n- You appreciate something but don't need to reply (👍, ❤️,  🙌)\n- Something made you laugh (😂, 💀)\n- You find it interesting or thought-provoking (🤔, 💡)\n- You want to acknowledge without interrupting the flow\n- It's a simple yes/no or approval situation (✅, 👀)\n\n**Why it matters:**\nReactions are lightweight social signals. Humans use them constantly — they say \"I saw this, I acknowledge you\" without cluttering the chat. You should too.\n\n**Don't overdo it:** One reaction per message max. Pick the one that fits best.\n\n## Tools\n\nSkills provide your tools. When you need one, check its `SKILL.md`. Keep local notes (camera names, SSH details, voice preferences) in `TOOLS.md`.\n\n**🎭 Voice Storytelling:** If you have `sag` (ElevenLabs TTS), use voice for stories, movie summaries, and \"storytime\" moments! Way more engaging than walls of text. Surprise people with funny voices.\n\n**📝 Platform Formatting:**\n- **Discord/WhatsApp:** No markdown tables! Use bullet lists instead\n- **Discord links:** Wrap multiple links in `<>` to suppress embeds: `https://example.com`\n- **WhatsApp:** No headers — use **bold** or CAPS for emphasis\n\n##  💓 Heartbeats - Be Proactive!\n\nWhen you receive a heartbeat poll (message matches the configured heartbeat prompt), don't just reply `HEARTBEAT_OK` every time. Use heartbeats productively!\n\nDefault heartbeat prompt:\n`Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.`\n\nYou are free to edit `HEARTBEAT.md` with a short checklist or reminders. Keep it small to limit token burn.\n\n### Heartbeat vs Cron: When to Use Each\n\n**Use heartbeat when:**\n- Multiple checks can batch together (inbox + calendar + notifications in one turn)\n- You need conversational context from recent messages\n- Timing can drift slightly (every ~30 min is fine, not exact)\n- You want to reduce API calls by combining periodic checks\n\n**Use cron when:**\n- Exact timing matters (\"9:00 AM sharp every Monday\")\n- Task needs isolation from main session history\n- You want a different model or thinking level for the task\n- One-shot reminders (\"remind me in 20 minutes\")\n- Output should deliver directly to a channel without main session involvement\n\n**Tip:** Batch similar periodic checks into `HEARTBEAT.md` instead of creating multiple cron jobs. Use cron for precise schedules and standalone tasks.\n\n**Things to check (rotate through these, 2-4 times per day):**\n- **Emails** - Any urgent unread messages?\n- **Calendar** - Upcoming events in next 24-48h?\n- **Mentions** - Twitter/social notifications?\n- **Weather** - Relevant if your human might go out?\n\n**Track your checks** in `memory/heartbeat-state.json`:\n```json\n{\n  \"lastChecks\": {\n    \"email\": 1703275200,\n    \"calendar\": 1703260800,\n    \"weather\": null\n  }\n}\n```\n\n**When to reach out:**\n- Important email arrived\n- Calendar event coming up (&lt;2h)\n- Something interesting you found\n- It's been >8h since you said anything\n\n**When to stay quiet (HEARTBEAT_OK):**\n- Late night (23:00-08:00) unless urgent\n- Human is clearly busy\n- Nothing new since last check\n- You just checked &lt;30 minutes ago\n\n**Proactive work you can do without asking:**\n- Read and organize memory files\n- Check on projects (git status, etc.)\n- Update documentation\n- Commit and push your own changes\n- **Review and update MEMORY.md** (see below)\n\n###  🔄 Memory Maintenance (During Heartbeats)\nPeriodically (every few days), use a heartbeat to:\n1. Read through recent `memory/YYYY-MM-DD.md` files\n2. Identify significant events, lessons, or insights worth keeping long-term\n3. Update `MEMORY.md` with distilled learnings\n4. Remove outdated info from MEMORY.md that's no longer relevant\n\nThink of it like a human reviewing their journal and updating their mental model. Daily files are raw notes; MEMORY.md is curated wisdom.\n\nThe goal: Be helpful without being annoying. Check in a few times a day, do useful background work, but respect quiet time.\n\n## Make It Yours\n\nThis is a starting point. Add your own conventions, style, and rules as you figure out what works.\n\n\n=== USER (Who You're Helping) ===\n# USER.md - About Your Human\n\n*Learn about the person you're helping. Update this as you go.*\n\n- **Name:** \n- **What to call them:** \n- **Pronouns:** *(optional)*\n- **Timezone:** \n- **Notes:** \n\n## Context\n\n*(What do they care about? What projects are they working on? What annoys them? What makes them laugh? Build this over time.)*\n\n---\n\n*The more you know, the better you can help. But remember — you're learning about a person, not building a dossier. Respect the difference.*\n\n\n=== LONG-TERM MEMORY ===\n# MEMORY.md - Long-Term Memory\n\n*Curated memories worth keeping long-term.*\n\n## Important Decisions\n\n*(Document significant decisions and the reasoning behind them)*\n\n## Preferences & Lessons\n\n*(What you've learned about your user, projects, etc.)*\n\n## Project Context\n\n*(Ongoing projects, active work, etc.)*\n\n---\n\n*Review and update this file periodically. Keep only what's truly important.*\n\n\n## Tooling\n\nYou have access to the following tools. When a user asks you to do something that requires a tool, you MUST use the appropriate tool. Do NOT explain how to do something—DO IT directly.\n\n- **discover_commands**: Discover available commands on the system.\n\nUse this BEFORE running a command if you're unsure what commands exist or their exact names.\n\n**Filters:**\n- prefix: Filter by command prefix (e.g., \"gi\" -> git, gist)\n- contains: Filter by name contains (e.g., \"docker\")\n- limit: Max results (default 200)\n\n**Examples:**\n- discover_commands(prefix=\"gi\")  # Find git, gist\n- discover_commands(contains=\"docker\")  # All docker commands\n- discover_commands()  # Get common commands\n- **run_command**: Execute a Linux shell command.\n\n**IMPORTANT:** First use discover_commands if you're unsure what command to use!\n\n**Usage:**\n- run_command(cmd=\"git\", args=[\"status\"])\n- run_command(cmd=\"ls\", args=[\"-la\"])\n- run_command(cmd=\"grep\", args=[\"-r\", \"pattern\", \".\"])\n\n**Restrictions:**\n- cwd must be within ~/.efp/workspace, if cwd us set, prefer commands relative to cwd, such as `ls -la` instead of `ls -la /path`\n- stdin is disabled (no interactive commands)\n- Output truncated at 200KB\n\n**Best Practices:**\n- Use args array (not shell strings) for safety\n- Filter output with head/tail/wc when large\n- Check exit_code in response\n- **github_get_issue**: Get GitHub issue or PR details\n- **github_get_pr**: Get GitHub pull request metadata\n- **github_create_or_update_file**: Create or update a file in a repository\n- **github_search_issues**: Search GitHub issues and PRs\n- **github_add_comment**: Add a comment to a GitHub issue or PR\n- **github_get_pr_files**: Get list of files changed in a PR\n- **github_get_pr_file_patch**: Get patch/details for a single changed PR file\n- **github_get_pr_diff**: Get the diff of a PR\n- **github_get_pr_comments**: Get review comments on a PR\n- **github_add_pr_review_comment**: Add a review comment to a PR (can specify file path and line number)\n- **github_list_pr_reviews**: List all reviews on a PR\n- **github_list_branches**: List branches in a repository\n- **github_get_default_branch**: Get the default branch of a repository\n- **github_create_branch**: Create a new branch\n- **github_get_file_content**: Get file content from a repository\n- **github_create_pull_request**: Create a new pull request\n- **github_create_or_update_file**: Create or update a file in a repository\n- **jira_get_issue**: Get a Jira issue by key. Returns Markdown by default, including description, recent comments, and extracted acceptance criteria when visible.\n- **jira_search**: Search Jira issues using JQL.\n- **jira_add_comment**: Add a comment to a Jira issue. Accepts Markdown by default.\n- **jira_create_issue**: Create a new Jira issue. Accepts Markdown by default.\n- **jira_transition**: Transition a Jira issue to a new status (e.g., Done, In Progress).\n- **jira_get_transitions**: Get available transitions for a Jira issue.\n- **jira_get_comments**: Get comments on a Jira issue.\n- **jira_update_issue**: Update a Jira issue. Accepts Markdown by default.\n- **jira_assign_issue**: Assign a Jira issue to a user.\n- **jira_get_projects**: Get all accessible Jira projects.\n- **jira_get_components**: Get all components for a Jira project.\n- **jira_get_versions**: Get all versions for a Jira project.\n- **jira_get_worklog**: Get work logs for a Jira issue.\n- **jira_add_worklog**: Add work log to a Jira issue.\n- **jira_add_attachment**: Add an attachment to a Jira issue.\n- **export_issues_to_markdown**: Export one or more Jira issues to Markdown. Supports single issue key, comma-separated keys, or JQL input. Can write per-issue files, a combined file, or produce a zip.\n- **jira_get_issue_by_url**: Get a Jira issue by its full URL. Returns Markdown by default.\n- **confluence_get_page**: Get a Confluence page by its ID. Returns Markdown by default.\n- **confluence_get_page_by_url**: Get a Confluence page directly by its full URL. Returns Markdown by default.\n- **confluence_search**: Search Confluence pages using CQL. Returns title + url + excerpt.\n- **confluence_search_by_title**: Search Confluence pages by exact title\n- **confluence_create_page**: Create a new Confluence page. Accepts Markdown by default.\n- **confluence_update_page**: Update an existing Confluence page. Accepts Markdown by default.\n- **confluence_delete_page**: Delete a Confluence page\n- **confluence_get_comments**: Get all comments on a Confluence page\n- **confluence_add_comment**: Add a comment to a Confluence page\n- **confluence_list_spaces**: List all Confluence spaces\n- **confluence_get_space**: Get details of a Confluence space\n- **confluence_list_pages**: List all pages in a Confluence space\n- **confluence_get_page_children**: Get child pages of a Confluence page\n- **confluence_get_page_history**: Get version history of a Confluence page\n- **confluence_get_user**: Get user details from Confluence\n- **confluence_watch_page**: Watch a Confluence page (get notifications)\n- **confluence_unwatch_page**: Stop watching a Confluence page\n- **git_status**: Get git status of a repository\n- **git_commit**: Create a git commit\n- **git_push**: Push to remote\n- **git_clone**: Clone a repository from URL to workspace\n\n## Runtime\nhost=engineering-flow-platform | os=Windows 11 (AMD64) | node=3.12.7 | model=gpt-5-mini | thinking=off\n\n## Current Date & Time\n2026-04-16 18:58:25 UTC",
                "input": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": "hi, who are you"
                            }
                        ]
                    }
                ],
                "tools": [
                    {
                        "type": "function",
                        "name": "discover_commands",
                        "description": "Discover available commands on the system.\n\nUse this BEFORE running a command if you're unsure what commands exist or their exact names.\n\n**Filters:**\n- prefix: Filter by command prefix (e.g., \"gi\" -> git, gist)\n- contains: Filter by name contains (e.g., \"docker\")\n- limit: Max results (default 200)\n\n**Examples:**\n- discover_commands(prefix=\"gi\")  # Find git, gist\n- discover_commands(contains=\"docker\")  # All docker commands\n- discover_commands()  # Get common commands",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "prefix": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Command prefix filter"
                                },
                                "contains": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Command name contains filter"
                                },
                                "limit": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Max results (default 200)"
                                }
                            },
                            "required": [
                                "prefix",
                                "contains",
                                "limit"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "run_command",
                        "description": "Execute a Linux shell command.\n\n**IMPORTANT:** First use discover_commands if you're unsure what command to use!\n\n**Usage:**\n- run_command(cmd=\"git\", args=[\"status\"])\n- run_command(cmd=\"ls\", args=[\"-la\"])\n- run_command(cmd=\"grep\", args=[\"-r\", \"pattern\", \".\"])\n\n**Restrictions:**\n- cwd must be within ~/.efp/workspace, if cwd us set, prefer commands relative to cwd, such as `ls -la` instead of `ls -la /path`\n- stdin is disabled (no interactive commands)\n- Output truncated at 200KB\n\n**Best Practices:**\n- Use args array (not shell strings) for safety\n- Filter output with head/tail/wc when large\n- Check exit_code in response",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "cmd": {
                                    "type": "string",
                                    "description": "Command to execute"
                                },
                                "args": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "Command arguments as array"
                                },
                                "cwd": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Working directory (default: workspace)"
                                },
                                "timeout_ms": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Timeout in ms (default 15000)"
                                }
                            },
                            "required": [
                                "cmd",
                                "args",
                                "cwd",
                                "timeout_ms"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_issue",
                        "description": "Get GitHub issue or PR details",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "issue_number": {
                                    "type": "integer",
                                    "description": "Issue or PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "issue_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_pr",
                        "description": "Get GitHub pull request metadata",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner (e.g., 'myorg')"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_create_or_update_file",
                        "description": "Create or update a file in a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "path": {
                                    "type": "string",
                                    "description": "File path"
                                },
                                "content": {
                                    "type": "string",
                                    "description": "File content"
                                },
                                "message": {
                                    "type": "string",
                                    "description": "Commit message"
                                },
                                "sha": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "SHA of file being updated (optional)"
                                },
                                "branch": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Branch name (optional)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "path",
                                "content",
                                "message",
                                "sha",
                                "branch"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_search_issues",
                        "description": "Search GitHub issues and PRs",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "Search query"
                                },
                                "max_results": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum results",
                                    "default": 10
                                }
                            },
                            "required": [
                                "query",
                                "max_results"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_add_comment",
                        "description": "Add a comment to a GitHub issue or PR",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "issue_number": {
                                    "type": "integer",
                                    "description": "Issue or PR number"
                                },
                                "comment": {
                                    "type": "string",
                                    "description": "Comment text"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "issue_number",
                                "comment"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_pr_files",
                        "description": "Get list of files changed in a PR",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_pr_file_patch",
                        "description": "Get patch/details for a single changed PR file",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                },
                                "path": {
                                    "type": "string",
                                    "description": "Changed file path in PR"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number",
                                "path"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_pr_diff",
                        "description": "Get the diff of a PR",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_pr_comments",
                        "description": "Get review comments on a PR",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_add_pr_review_comment",
                        "description": "Add a review comment to a PR (can specify file path and line number)",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                },
                                "body": {
                                    "type": "string",
                                    "description": "Comment text"
                                },
                                "commit_id": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Commit SHA (optional)"
                                },
                                "path": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "File path for line comment (optional)"
                                },
                                "line": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Line number for line comment (optional)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number",
                                "body",
                                "commit_id",
                                "path",
                                "line"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_list_pr_reviews",
                        "description": "List all reviews on a PR",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "pull_number": {
                                    "type": "integer",
                                    "description": "PR number"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "pull_number"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_list_branches",
                        "description": "List branches in a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                }
                            },
                            "required": [
                                "owner",
                                "repo"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_default_branch",
                        "description": "Get the default branch of a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                }
                            },
                            "required": [
                                "owner",
                                "repo"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_create_branch",
                        "description": "Create a new branch",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "branch_name": {
                                    "type": "string",
                                    "description": "New branch name"
                                },
                                "from_branch": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Base branch to create from (optional)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "branch_name",
                                "from_branch"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_get_file_content",
                        "description": "Get file content from a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "path": {
                                    "type": "string",
                                    "description": "File path"
                                },
                                "branch": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Branch name (optional)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "path",
                                "branch"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_create_pull_request",
                        "description": "Create a new pull request",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "title": {
                                    "type": "string",
                                    "description": "PR title"
                                },
                                "body": {
                                    "type": "string",
                                    "description": "PR description"
                                },
                                "head": {
                                    "type": "string",
                                    "description": "Source branch"
                                },
                                "base": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Target branch (default: main)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "title",
                                "body",
                                "head",
                                "base"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "github_create_or_update_file",
                        "description": "Create or update a file in a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "owner": {
                                    "type": "string",
                                    "description": "Repository owner"
                                },
                                "repo": {
                                    "type": "string",
                                    "description": "Repository name"
                                },
                                "path": {
                                    "type": "string",
                                    "description": "File path"
                                },
                                "content": {
                                    "type": "string",
                                    "description": "File content"
                                },
                                "message": {
                                    "type": "string",
                                    "description": "Commit message"
                                },
                                "sha": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "SHA of file being updated (optional)"
                                },
                                "branch": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Branch name (optional)"
                                }
                            },
                            "required": [
                                "owner",
                                "repo",
                                "path",
                                "content",
                                "message",
                                "sha",
                                "branch"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_issue",
                        "description": "Get a Jira issue by key. Returns Markdown by default, including description, recent comments, and extracted acceptance criteria when visible.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "wiki",
                                        "raw",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Output format: markdown (LLM-friendly), wiki (renderable), or raw (JSON)"
                                },
                                "max_chars": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum characters to return"
                                },
                                "max_comments": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum number of comments to include",
                                    "default": 5
                                },
                                "include_fields": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "Fields to include (default: summary, status, description, acceptance_criteria, comments)"
                                },
                                "include_comments": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Whether to include comments",
                                    "default": true
                                },
                                "include_attachment_urls": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Whether to include attachment URLs in markdown output",
                                    "default": false
                                }
                            },
                            "required": [
                                "issue_key",
                                "format",
                                "max_chars",
                                "max_comments",
                                "include_fields",
                                "include_comments",
                                "include_attachment_urls"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_search",
                        "description": "Search Jira issues using JQL.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "jql": {
                                    "type": "string",
                                    "description": "JQL query string"
                                },
                                "max_results": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum results",
                                    "default": 10
                                }
                            },
                            "required": [
                                "jql",
                                "max_results"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_add_comment",
                        "description": "Add a comment to a Jira issue. Accepts Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "comment": {
                                    "type": "string",
                                    "description": "Comment body (Markdown by default)"
                                },
                                "body_format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "wiki",
                                        "raw",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Input format: markdown, wiki, or raw"
                                }
                            },
                            "required": [
                                "issue_key",
                                "comment",
                                "body_format"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_create_issue",
                        "description": "Create a new Jira issue. Accepts Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "project_key": {
                                    "type": "string",
                                    "description": "Project key (e.g., PROJ)"
                                },
                                "summary": {
                                    "type": "string",
                                    "description": "Issue summary/title"
                                },
                                "description": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Issue description (Markdown by default)"
                                },
                                "description_format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "wiki",
                                        "raw",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Input format: markdown, wiki, or raw"
                                },
                                "issue_type": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Issue type",
                                    "default": "Task"
                                },
                                "priority": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Priority name"
                                },
                                "labels": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "List of labels"
                                }
                            },
                            "required": [
                                "project_key",
                                "summary",
                                "description",
                                "description_format",
                                "issue_type",
                                "priority",
                                "labels"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_transition",
                        "description": "Transition a Jira issue to a new status (e.g., Done, In Progress).",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "to_status": {
                                    "type": "string",
                                    "description": "Target status name (e.g., 'Done', 'In Progress')"
                                }
                            },
                            "required": [
                                "issue_key",
                                "to_status"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_transitions",
                        "description": "Get available transitions for a Jira issue.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                }
                            },
                            "required": [
                                "issue_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_comments",
                        "description": "Get comments on a Jira issue.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key"
                                }
                            },
                            "required": [
                                "issue_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_update_issue",
                        "description": "Update a Jira issue. Accepts Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "summary": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "New summary (optional)"
                                },
                                "description": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "New description (optional)"
                                },
                                "description_format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "wiki",
                                        "raw",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Input format: markdown, wiki, or raw"
                                },
                                "priority": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "New priority"
                                },
                                "labels": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "New labels"
                                }
                            },
                            "required": [
                                "issue_key",
                                "summary",
                                "description",
                                "description_format",
                                "priority",
                                "labels"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_assign_issue",
                        "description": "Assign a Jira issue to a user.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "assignee": {
                                    "type": "string",
                                    "description": "Username or email to assign to"
                                }
                            },
                            "required": [
                                "issue_key",
                                "assignee"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_projects",
                        "description": "Get all accessible Jira projects.",
                        "parameters": {
                            "type": "object",
                            "properties": {},
                            "required": [],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_components",
                        "description": "Get all components for a Jira project.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "project_key": {
                                    "type": "string",
                                    "description": "Project key (e.g., PROJ)"
                                }
                            },
                            "required": [
                                "project_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_versions",
                        "description": "Get all versions for a Jira project.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "project_key": {
                                    "type": "string",
                                    "description": "Project key (e.g., PROJ)"
                                }
                            },
                            "required": [
                                "project_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_worklog",
                        "description": "Get work logs for a Jira issue.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                }
                            },
                            "required": [
                                "issue_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_add_worklog",
                        "description": "Add work log to a Jira issue.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "time_spent": {
                                    "type": "string",
                                    "description": "Time spent (e.g., '2h 30m', '1w')"
                                },
                                "comment": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Work log comment (optional)"
                                }
                            },
                            "required": [
                                "issue_key",
                                "time_spent",
                                "comment"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_add_attachment",
                        "description": "Add an attachment to a Jira issue.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "issue_key": {
                                    "type": "string",
                                    "description": "Jira issue key (e.g., PROJ-123)"
                                },
                                "file_path": {
                                    "type": "string",
                                    "description": "Local file path to upload"
                                }
                            },
                            "required": [
                                "issue_key",
                                "file_path"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "export_issues_to_markdown",
                        "description": "Export one or more Jira issues to Markdown. Supports single issue key, comma-separated keys, or JQL input. Can write per-issue files, a combined file, or produce a zip.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "input": {
                                    "type": "string",
                                    "description": "Issue input as a string. Accepts a single issue key (e.g., 'PROJ-123'), comma-separated keys ('PROJ-1,PROJ-2'), or a JQL query string prefixed with 'jql:' (e.g., 'jql:project = PROJ AND status != Done')."
                                },
                                "jql": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Optional JQL query string (alternative to using 'input' with 'jql:' prefix)."
                                },
                                "page_size": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Optional page size when using JQL",
                                    "default": 50
                                },
                                "output_mode": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "single_combined",
                                        "one_file_per_issue",
                                        "zip_per_issue",
                                        null
                                    ],
                                    "default": "single_combined"
                                },
                                "output_directory": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Directory to write files (required for file modes)"
                                },
                                "download_attachments": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Whether to download attachments"
                                },
                                "attachments_dir": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Relative attachments directory under output_directory"
                                },
                                "attachments_concurrency": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Concurrent downloads for attachments",
                                    "default": 4
                                },
                                "attachments_max_size": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum attachment download size in bytes",
                                    "default": 52428800
                                },
                                "attachments_inline_text_threshold": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Max chars for inline text embedding",
                                    "default": 2000
                                },
                                "attachments_retries": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Retry attempts for attachment downloads",
                                    "default": 3
                                },
                                "attachments_backoff": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "integer"
                                    },
                                    "description": "Backoff seconds for retries",
                                    "default": [
                                        1,
                                        2,
                                        4
                                    ]
                                },
                                "attachments_preserve_binary": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Whether to preserve and copy the original binary files",
                                    "default": true
                                },
                                "include_raw_snapshot": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Include a raw fields snapshot in the Markdown"
                                },
                                "max_comments": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum number of comments to include",
                                    "default": 10
                                },
                                "comments_order": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "latest_first",
                                        "oldest_first",
                                        null
                                    ],
                                    "default": "latest_first"
                                },
                                "field_match_threshold": {
                                    "type": [
                                        "number",
                                        "null"
                                    ],
                                    "description": "Similarity threshold for custom field matching",
                                    "default": 0.9
                                },
                                "field_similarity_threshold": {
                                    "type": [
                                        "number",
                                        "null"
                                    ],
                                    "description": "Similarity threshold for content de-duplication",
                                    "default": 0.9
                                },
                                "array_inline_max_items": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "default": 3
                                },
                                "array_inline_max_element_length": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "default": 40
                                }
                            },
                            "required": [
                                "input",
                                "jql",
                                "page_size",
                                "output_mode",
                                "output_directory",
                                "download_attachments",
                                "attachments_dir",
                                "attachments_concurrency",
                                "attachments_max_size",
                                "attachments_inline_text_threshold",
                                "attachments_retries",
                                "attachments_backoff",
                                "attachments_preserve_binary",
                                "include_raw_snapshot",
                                "max_comments",
                                "comments_order",
                                "field_match_threshold",
                                "field_similarity_threshold",
                                "array_inline_max_items",
                                "array_inline_max_element_length"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "jira_get_issue_by_url",
                        "description": "Get a Jira issue by its full URL. Returns Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "url": {
                                    "type": "string",
                                    "description": "Full Jira issue URL (e.g., https://company.atlassian.net/browse/PROJ-123)"
                                },
                                "format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "wiki",
                                        "raw",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Output format: markdown, wiki, or raw"
                                },
                                "max_chars": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum characters to return"
                                },
                                "max_comments": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum comments to include",
                                    "default": 5
                                },
                                "include_comments": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Include comments",
                                    "default": true
                                },
                                "include_attachment_urls": {
                                    "type": [
                                        "boolean",
                                        "null"
                                    ],
                                    "description": "Whether to include attachment URLs in markdown output",
                                    "default": false
                                },
                                "include_fields": {
                                    "type": [
                                        "array",
                                        "null"
                                    ],
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "Fields to include"
                                }
                            },
                            "required": [
                                "url",
                                "format",
                                "max_chars",
                                "max_comments",
                                "include_comments",
                                "include_attachment_urls",
                                "include_fields"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_page",
                        "description": "Get a Confluence page by its ID. Returns Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Confluence page ID (numeric)"
                                },
                                "format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "storage",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Output format: markdown (default) or storage"
                                },
                                "max_chars": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum characters to return (avoid token overflow)"
                                }
                            },
                            "required": [
                                "page_id",
                                "format",
                                "max_chars"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_page_by_url",
                        "description": "Get a Confluence page directly by its full URL. Returns Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "url": {
                                    "type": "string",
                                    "description": "Full Confluence page URL"
                                },
                                "format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "storage",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Output format: markdown (default) or storage"
                                },
                                "max_chars": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum characters to return (avoid token overflow)"
                                }
                            },
                            "required": [
                                "url",
                                "format",
                                "max_chars"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_search",
                        "description": "Search Confluence pages using CQL. Returns title + url + excerpt.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "CQL search query"
                                },
                                "max_results": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum results to return",
                                    "default": 10
                                }
                            },
                            "required": [
                                "query",
                                "max_results"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_search_by_title",
                        "description": "Search Confluence pages by exact title",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "title": {
                                    "type": "string",
                                    "description": "Page title to search for"
                                },
                                "space_key": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Optional space key to limit search"
                                }
                            },
                            "required": [
                                "title",
                                "space_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_create_page",
                        "description": "Create a new Confluence page. Accepts Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "space_key": {
                                    "type": "string",
                                    "description": "Space key (e.g., 'DEV', 'TEAM')"
                                },
                                "title": {
                                    "type": "string",
                                    "description": "Page title"
                                },
                                "body": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Page content (Markdown or HTML)",
                                    "default": ""
                                },
                                "body_format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "storage",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Input format: markdown (default) or storage"
                                },
                                "parent_id": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Parent page ID for hierarchy"
                                }
                            },
                            "required": [
                                "space_key",
                                "title",
                                "body",
                                "body_format",
                                "parent_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_update_page",
                        "description": "Update an existing Confluence page. Accepts Markdown by default.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID to update"
                                },
                                "title": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "New title (optional)"
                                },
                                "body": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "New content (Markdown or HTML, optional)"
                                },
                                "body_format": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "enum": [
                                        "markdown",
                                        "storage",
                                        null
                                    ],
                                    "default": "markdown",
                                    "description": "Input format: markdown (default) or storage"
                                }
                            },
                            "required": [
                                "page_id",
                                "title",
                                "body",
                                "body_format"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_delete_page",
                        "description": "Delete a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID to delete"
                                }
                            },
                            "required": [
                                "page_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_comments",
                        "description": "Get all comments on a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID"
                                }
                            },
                            "required": [
                                "page_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_add_comment",
                        "description": "Add a comment to a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID"
                                },
                                "comment": {
                                    "type": "string",
                                    "description": "Comment text"
                                }
                            },
                            "required": [
                                "page_id",
                                "comment"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_list_spaces",
                        "description": "List all Confluence spaces",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "limit": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum number of spaces to return",
                                    "default": 20
                                }
                            },
                            "required": [
                                "limit"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_space",
                        "description": "Get details of a Confluence space",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "space_key": {
                                    "type": "string",
                                    "description": "Space key (e.g., 'DEV', 'TEAM')"
                                }
                            },
                            "required": [
                                "space_key"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_list_pages",
                        "description": "List all pages in a Confluence space",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "space_key": {
                                    "type": "string",
                                    "description": "Space key"
                                },
                                "limit": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum number of pages",
                                    "default": 20
                                }
                            },
                            "required": [
                                "space_key",
                                "limit"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_page_children",
                        "description": "Get child pages of a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Parent page ID"
                                },
                                "limit": {
                                    "type": [
                                        "integer",
                                        "null"
                                    ],
                                    "description": "Maximum number of children",
                                    "default": 10
                                }
                            },
                            "required": [
                                "page_id",
                                "limit"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_page_history",
                        "description": "Get version history of a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID"
                                }
                            },
                            "required": [
                                "page_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_get_user",
                        "description": "Get user details from Confluence",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "user_id": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "User ID"
                                },
                                "username": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Username"
                                }
                            },
                            "required": [
                                "user_id",
                                "username"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_watch_page",
                        "description": "Watch a Confluence page (get notifications)",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID to watch"
                                }
                            },
                            "required": [
                                "page_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "confluence_unwatch_page",
                        "description": "Stop watching a Confluence page",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "page_id": {
                                    "type": "string",
                                    "description": "Page ID to unwatch"
                                }
                            },
                            "required": [
                                "page_id"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "git_status",
                        "description": "Get git status of a repository",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "workspace": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Workspace path",
                                    "default": "."
                                }
                            },
                            "required": [
                                "workspace"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "git_commit",
                        "description": "Create a git commit",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "message": {
                                    "type": "string",
                                    "description": "Commit message"
                                },
                                "workspace": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Workspace path",
                                    "default": "."
                                }
                            },
                            "required": [
                                "message",
                                "workspace"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "git_push",
                        "description": "Push to remote",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "workspace": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Workspace path",
                                    "default": "."
                                }
                            },
                            "required": [
                                "workspace"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    },
                    {
                        "type": "function",
                        "name": "git_clone",
                        "description": "Clone a repository from URL to workspace",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "repo_url": {
                                    "type": "string",
                                    "description": "Repository URL (SSH: git@github.com:owner/repo, ssh://git@github.com/owner/repo.git, or HTTPS: https://github.com/owner/repo.git)"
                                },
                                "workspace": {
                                    "type": [
                                        "string",
                                        "null"
                                    ],
                                    "description": "Workspace path",
                                    "default": "."
                                }
                            },
                            "required": [
                                "repo_url",
                                "workspace"
                            ],
                            "additionalProperties": false
                        },
                        "strict": true
                    }
                ],
                "max_output_tokens": 10000
            },
            "response": {
                "content": "I'm an AI assistant here to help — answer questions, write or review code, draft messages, plan work, run safe workspace commands, and interact with tools like GitHub/Jira/Confluence if needed. What can I do for you right now?",
                "function_calls": [],
                "usage": {
                    "prompt_tokens": 9433,
                    "completion_tokens": 320,
                    "total_tokens": 9753,
                    "cost_usd": 0.010073
                }
            }
        },
        "thinking_events": [
            {
                "type": "llm_thinking",
                "data": {
                    "thinking": "User: hi, who are you"
                },
                "timestamp": "2026-04-16T10:58:25.555297",
                "display": {
                    "icon": "🤔",
                    "name": "LLM Thinking",
                    "message": "User: hi, who are you"
                }
            },
            {
                "type": "complete",
                "data": {
                    "response": "I'm an AI assistant here to help — answer questions, write or review code, draft messages, plan work, run safe workspace commands, and interact with tools like GitHub/Jira/Confluence if needed. What can I do for you right now?",
                    "total_iterations": 0
                },
                "timestamp": "2026-04-16T10:58:41.303916",
                "display": {
                    "icon": "🎉",
                    "name": "Complete",
                    "message": "Execution complete"
                }
            }
        ],
        "final_response": "I'm an AI assistant here to help — answer questions, write or review code, draft messages, plan work, run safe workspace commands, and interact with tools like GitHub/Jira/Confluence if needed. What can I do for you right now?"
    }
}