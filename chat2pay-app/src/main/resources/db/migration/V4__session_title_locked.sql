-- Allow the AI title-suggestion flow to update titles only when the user has
-- not manually renamed the session. Defaults to false so existing rows are
-- still candidates for an AI-generated title.

alter table ctp_chat_session
    add column if not exists title_locked boolean not null default false;
