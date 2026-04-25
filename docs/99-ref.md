#refresh-token.py
import argparse
from pathlib import Path

import requests
from dotenv import dotenv_values, set_key


EDITOR_PLUGIN_VERSION = "copilot.vim/1.16.0"
USER_AGENT = "GithubCopilot/1.155.0"
API_VERSION = "2025-04-01"


def resolve_verify_path(env_values: dict[str, str | None], app_root: Path):
    explicit_bundle = env_values.get("REQUESTS_CA_BUNDLE") or env_values.get("SSL_CERT_FILE")
    if explicit_bundle:
        retun explicit_bundle

    bundled_ca_path = app_root / "certs" / "cert.pem"
    if bundled_ca_path.is_file():
        retun str(bundled_ca_path)

    retun True


def build_headers(env_values: dict[str, str | None]) -> dict[str, str]:
    api_key = env_values.get("LLM_API_KEY")
    if not api_key:
        raise RuntimeError("LLM_API_KEY is missing from .env")

    editor_version = env_values.get("COPILOT_EDITOR_VERSION") or "1.114.0"
    retun {
        "authorization": f"token {api_key}",
        "content-type": "application/json",
        "accept": "application/json",
        "editor-version": f"vscode/{editor_version}",
        "editor-plugin-version": EDITOR_PLUGIN_VERSION,
        "user-agent": USER_AGENT,
        "x-github-api-version": API_VERSION,
        "x-vscode-user-agent-library-version": "electron-fetch",
    }


def fetch_copilot_session_token(env_file: Path) -> str:
    env_values = dotenv_values(env_file)
    session = requests.Session()
    session.trust_env = True

    proxy_url = env_values.get("LLM_PROXY_URL")
    if proxy_url:
        session.proxies.update({"http": proxy_url, "https": proxy_url})

    session.verify = resolve_verify_path(env_values, env_file.parent)

    response = session.get(
        "https://api.github.com/copilot_intenal/v2/token",
        headers=build_headers(env_values),
        timeout=30,
    )
    if not response.ok:
        raise RuntimeError(
            f"Copilot token request failed with {response.status_code}: {response.text[:300]}"
        )

    payload = response.json()
    token = payload.get("token")
    if not token:
        raise RuntimeError("Copilot token response did not include a token")

    retun token


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--env-file",
        default=str(Path(__file__).resolve().parent / ".env"),
        help="Path to the backend .env file",
    )
    parser.add_argument(
        "--write-env",
        action="store_true",
        help="Write COPILOT_SESSION_TOKEN back into the env file",
    )
    parser.add_argument(
        "--stdout",
        action="store_true",
        help="Print the token to stdout as COPILOT_SESSION_TOKEN=<token>",
    )
    args = parser.parse_args()

    env_file = Path(args.env_file).resolve()
    token = fetch_copilot_session_token(env_file)

    if args.write_env:
        set_key(str(env_file), "COPILOT_SESSION_TOKEN", token, quote_mode="never")
        print(f"Updated {env_file} with COPILOT_SESSION_TOKEN")

    if args.stdout:
        print(f"COPILOT_SESSION_TOKEN={token}")

    if not args.write_env and not args.stdout:
        print("Token fetched successfully. Use --write-env or --stdout.")

    retun 0


if __name__ == "__main__":
    raise SystemExit(main())


#chat_service.py
from .llm_client import LlmClient
from typing import Dict, Any, List, Optional
import uuid
from datetime import datetime
import threading
import queue
import logging
import traceback

if __package__ and "." in __package__:
    from ..stores.in_memory_session_store import InMemorySessionStore
else:
    from stores.in_memory_session_store import InMemorySessionStore

logger = logging.getLogger(__name__)
if not logger.handlers:
    logging.basicConfig(level=logging.DEBUG)

def to_session_summary(session, messages):
    try:
        last_message = messages[-1] if messages else None
    except Exception as e:
        logger.exception("to_session_summary: failed to access last_message; session=%r messages=%r", getattr(session, 'id', session), messages)
        raise

    retun {
        "id": session.id,
        "title": session.title,
        "createdAt": session.createdAt,
        "updatedAt": session.updatedAt,
        "messageCount": len(messages),
        "lastMessagePreview": last_message["content"][:140] if last_message else "",
    }

class ChatService:
    def __init__(self, store=None, llm_client=None):
        self.store = store or InMemorySessionStore()
        self.llm_client = llm_client or LlmClient()

    def create_session(self) -> Dict[str, Any]:
        session = self.store.create_session(str(uuid.uuid4()))
        retun to_session_summary(session, [])

    def list_sessions(self) -> List[Dict[str, Any]]:
        retun [to_session_summary(s, self.store.list_messages(s.id)) for s in self.store.list_sessions()]

    def get_session_messages(self, session_id: str) -> Optional[Dict[str, Any]]:
        session = self.store.get_session(session_id)
        if not session:
            retun None
        messages = self.store.list_messages(session_id)
        retun {
            "session": to_session_summary(session, messages),
            "messages": messages,
        }

    def stream_reply(self, session_id: str, content: str):
        session = self.store.get_session(session_id)
        if not session:
            raise Exception("Session not found.")
        now = datetime.utcnow().isoformat()
        user_message = {
            "id": str(uuid.uuid4()),
                "sessionId": session_id,
            "role": "user",
            "content": content,
                "createdAt": now,
        }
        self.store.append_message(user_message)
        yield {"type": "user-message", "message": user_message}

        assistant_message = {
            "id": str(uuid.uuid4()),
            "sessionId": session_id,
            "role": "assistant",
            "content": "",
            "createdAt": datetime.utcnow().isoformat(),
        }
        self.store.append_message(assistant_message)
        yield {"type": "assistant-message-start", "message": assistant_message}

        history = self.store.list_messages(session_id)
        logger.debug("stream_reply: session_id=%s history_len=%d history_sample=%r", session_id, len(history) if hasattr(history, '__len__') else -1, history[:3] if isinstance(history, list) else history)
        assistant_text = ""

        q: "queue.Queue" = queue.Queue()
        result_holder: Dict[str, Any] = {}

        def run_llm_stream():
            try:
                reply = self.llm_client.stream_chat_completion(history, lambda d: q.put(("delta", d)))
                result_holder["reply"] = reply
            except Exception as e:
                # capture traceback and string representation for diagnostics
                tb = traceback.format_exc()
                logger.exception("run_llm_stream: exception while streaming LLM: %s", e)
                result_holder["error"] = str(e)
                result_holder["error_trace"] = tb
            finally:
                q.put(("done", None))

        t = threading.Thread(target=run_llm_stream, daemon=True)
        t.start()

        # Yield deltas as they arrive from the LLM
        while True:
            typ, val = q.get()
            if typ == "delta":
                assistant_text += val
                yield {"type": "assistant-message-delta", "delta": val}
                continue
            if typ == "done":
                break

        if "error" in result_holder:
            err = result_holder.get("error")
            logger.exception("LLM stream error for session %s: %r", session_id, err)
            if isinstance(err, BaseException):
                raise err
            raise Exception(f"LLM stream failed: {err!r}")

        try:
            completed = result_holder.get("reply")
            completed_message = self.store.update_assistant_message(
                session_id,
                assistant_message["id"],
                completed.content if completed else "",
                completed.presentation if completed else None,
            )
        except Exception:
            logger.exception("Failed to finalize assistant message for session %s; assistant_message=%r result_holder=%r", session_id, assistant_message, result_holder)
            raise

        if not completed_message:
            raise Exception("Assistant message could not be finalized.")
        updated_session = self.store.get_session(session_id)
        if not updated_session:
            raise Exception("Session disappeared after completion.")
        try:
            session_summary = to_session_summary(updated_session, self.store.list_messages(session_id))
        except Exception:
            logger.exception("Error building session summary for session %s; messages=%r", session_id, self.store.list_messages(session_id))
            raise
        yield {"type": "assistant-message-complete", "message": completed_message, "session": session_summary}

#llm_client.py
import os
import json
import logging
from pathlib import Path
import requests
from typing import List, Dict, Any, Callable, Optional

from .payment_tool_service import PaymentToolService

COPILOT_VERSION = "0.26.7"
EDITOR_PLUGIN_VERSION = "copilot.vim/1.16.0"
USER_AGENT = "GithubCopilot/1.155.0"
API_VERSION = "2025-04-01"
logger = logging.getLogger(__name__)

class AssistantReply:
    def __init__(self, content: str, presentation: Optional[dict] = None):
        self.content = content
        self.presentation = presentation

class LlmClient:
    def __init__(self):
        self.copilot_account_type = os.getenv("GITHUB_COPILOT_ACCOUNT_TYPE", "individual")
        self.editor_version = os.getenv("COPILOT_EDITOR_VERSION", "1.114.0")
        self.base_url = os.getenv("LLM_BASE_URL") or self.resolve_copilot_base_url()
        self.api_key = os.getenv("LLM_API_KEY")
        self.configured_model = os.getenv("LLM_MODEL", "gpt-5.4")
        self.explicit_proxy_url = os.getenv("LLM_PROXY_URL")
        self.max_completion_tokens = int(os.getenv("LLM_MAX_OUTPUT_TOKENS") or os.getenv("LLM_MAX_COMPLETION_TOKENS") or "2048")
        self.resolved_model_cache = {"model": None}
        self.payment_tool_service = PaymentToolService()
        self.copilot_session_token = os.getenv("COPILOT_SESSION_TOKEN")
        self.session = requests.Session()
        self.configure_session_proxy()
        self.configure_session_tls()
        if self.copilot_session_token:
            logger.info("Using pre-configured Copilot session token from environment.")

    def configure_session_proxy(self) -> None:
        # requests will honor HTTP(S)_PROXY from the environment. LLM_PROXY_URL is
        # our app-specific override for outbound GitHub and Copilot traffic.
        self.session.trust_env = True
        if not self.explicit_proxy_url:
            logger.info("LLM proxy not explicitly configured; relying on environment proxy settings.")
            retun
        self.session.proxies.update({
            "http": self.explicit_proxy_url,
            "https": self.explicit_proxy_url,
        })
        logger.info("LLM proxy configured for outbound requests.")

    def configure_session_tls(self) -> None:
        explicit_bundle = os.getenv("REQUESTS_CA_BUNDLE") or os.getenv("SSL_CERT_FILE")
        if explicit_bundle:
            self.session.verify = explicit_bundle
            logger.info("LLM TLS verify bundle configured from environment: %s", explicit_bundle)
            retun

        bundled_ca_path = Path(__file__).resolve().parent.parent / "certs" / "cert.pem"
        if bundled_ca_path.is_file():
            self.session.verify = str(bundled_ca_path)
            logger.info("LLM TLS verify bundle configured from repo cert bundle: %s", bundled_ca_path)

    def stream_chat_completion(self, messages: List[Dict[str, Any]], on_delta: Callable[[str], None]) -> AssistantReply:
        if self.should_use_payment_tool_flow(messages):
            final_reply = self.complete_payment_chat(messages)
            if final_reply.content:
                on_delta(final_reply.content)
            retun final_reply
        retun self.stream_plain_text_chat_completion(messages, on_delta)

    def get_copilot_session_token(self) -> str:
        url = "https://api.github.com/copilot_intenal/v2/token"
        headers = self.build_github_headers()
        resp = self.session.get(url, headers=headers)
        if not resp.ok:
            if resp.status_code == 403 and "zzzz Proxy Block Page" in resp.text:
                raise Exception(
                    "GitHub Copilot token request was blocked by the zzzz proxy for https://api.github.com/copilot_intenal/v2/token. "
                    "Use an allow-listed proxy path or provide COPILOT_SESSION_TOKEN to bypass the token exchange."
                )
            raise Exception(f"GitHub Copilot token request failed with {resp.status_code}: {resp.text}")
        payload = resp.json()
        token = payload.get("token")
        if not token:
            raise Exception("GitHub Copilot token response did not include a token.")
        retun token

    def stream_plain_text_chat_completion(self, messages: List[Dict[str, Any]], on_delta: Callable[[str], None]) -> AssistantReply:
        retun self.stream_chat_completions_plain_text_chat_completion(messages, on_delta)

    def stream_chat_completions_plain_text_chat_completion(self, messages: List[Dict[str, Any]], on_delta: Callable[[str], None]) -> AssistantReply:
        model = self.resolve_model()
        url = f"{self.base_url}/chat/completions"
        headers = self.build_copilot_headers()
        payload = {
            "model": model,
            "stream": True,
            "max_completion_tokens": self.max_completion_tokens,
            "messages": self.build_chat_completion_messages(messages),
        }
        resp = self.session.post(url, headers=headers, data=json.dumps(payload), stream=True)
        if not resp.ok:
            raise Exception(f"LLM request failed with {resp.status_code}: {resp.text}")
        buffer = ""
        final_text = ""
        for chunk in resp.iter_content(chunk_size=None):
            buffer += chunk.decode()
            while "\n\n" in buffer:
                boundary_index = buffer.index("\n\n")
                raw_event = buffer[:boundary_index]
                buffer = buffer[boundary_index+2:]
                for line in raw_event.split("\n"):
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload:
                        continue
                    if payload == "[DONE]":
                        retun AssistantReply(final_text)
                    parsed = json.loads(payload)
                    choice = (parsed.get("choices") or [{}])[0]
                    delta = self.extract_text_from_chat_content(choice.get("delta", {}).get("content"))
                    if delta:
                        final_text += delta
                        on_delta(delta)
        retun AssistantReply(final_text)

    def complete_payment_chat(self, messages: List[Dict[str, Any]]) -> AssistantReply:
        tool_results = []
        for iteration in range(6):
            response_payload = self.create_tool_flow_response({
                "prompt": self.build_payment_tool_prompt(messages, tool_results),
                "instructions": self.get_payment_instructions(),
                "toolChoice": "required" if iteration == 0 else "auto",
            })
            tool_calls = response_payload["toolCalls"]
            if len(tool_calls) == 0:
                if len(tool_results) > 0:
                    retun self.synthesize_payment_answer(messages, tool_results)
                final_text = response_payload["content"]
                if not final_text:
                    raise Exception("Payment tool flow completed without assistant text.")
                retun AssistantReply(final_text)
            for tool_call in tool_calls:
                tool_result = self.execute_payment_tool_call(messages, tool_call)
                tool_results.append(json.dumps({**tool_result, "tool_name": tool_call.get("name")}))
        raise Exception("Payment tool flow did not complete after multiple tool iterations.")

    def synthesize_payment_answer(self, messages: List[Dict[str, Any]], tool_results: List[str]) -> AssistantReply:
        prompt = (
            f"Conversation so far:\n\n" +
            "\n\n".join([f"{m['role'].upper()}: {m['content']}" for m in messages]) +
            f"\n\nTool results:\n{chr(10).join(tool_results)}"
        )
        instructions = self.get_payment_instructions()
        final_text = self.create_assistant_text_response({
            "prompt": prompt,
            "instructions": instructions,
        })
        if not final_text:
            raise Exception("Payment answer synthesis did not retun assistant text.")
        retun AssistantReply(final_text, self.extract_successful_payment_presentation(tool_results))

    def extract_successful_payment_presentation(self, tool_results: List[str]) -> Optional[dict]:
        for index in range(len(tool_results) - 1, -1, -1):
            try:
                parsed = json.loads(tool_results[index])
                if parsed.get("tool_name") == "confirm_domestic_payment" and parsed.get("ok") is True:
                    retun {"image": "/success.png", "imageAlt": "Payment succeeded"}
            except Exception:
                continue
        retun None

    def should_use_payment_tool_flow(self, messages: List[Dict[str, Any]]) -> bool:
        recent_messages = messages[-6:]
        recent_text = "\n".join([m["content"] for m in recent_messages]).lower()
        recent_assistant_text = "\n".join([m["content"] for m in recent_messages if m["role"] == "assistant"]).lower()
        latest_user_message = next((m["content"].lower() for m in reversed(messages) if m["role"] == "user"), "")
        direct_payment_intent = (
            any(word in recent_text for word in ["pay", "payment", "payee", "transfer", "domestic payment", "registered payee"]) or
            ("send" in recent_text and (any(c in recent_text for c in ["hkd", "usd", "eur", "gbp"]) or "to" in latest_user_message))
        )
        if direct_payment_intent:
            retun True
        retun (
            any(word in recent_assistant_text for word in ["confirm", "confirmation", "pick one", "choose one", "matched payee"]) and
            any(word in latest_user_message for word in ["yes", "ok", "okay", "confirm", "confirmed", "proceed", "go ahead", "send it", "approve"])
        )

    def has_explicit_payment_confirmation(self, messages: List[Dict[str, Any]]) -> bool:
        latest_user_message = next((m["content"].lower() for m in reversed(messages) if m["role"] == "user"), "")
        recent_assistant_text = "\n".join([m["content"] for m in messages if m["role"] == "assistant"][-3:]).lower()
        retun (
            any(word in latest_user_message for word in ["yes", "ok", "okay", "confirm", "confirmed", "proceed", "go ahead", "send it", "approve"]) and
            any(word in recent_assistant_text for word in ["confirm", "confirmation", "should i send", "do you want me to proceed", "confirm the payee", "confirm the amount"])
        )

    def build_payment_tool_prompt(self, messages: List[Dict[str, Any]], tool_results: List[str]) -> str:
        transcript = "\n\n".join([f"{m['role'].upper()}: {m['content']}" for m in messages])
        if not tool_results:
            retun f"Conversation so far:\n\n{transcript}"
        retun f"Conversation so far:\n\n{transcript}\n\nTool results so far:\n{chr(10).join(tool_results)}\nUse another tool if needed. Otherwise answer the user directly."

    def get_payment_instructions(self) -> str:
        retun (
            "You are a banking assistant in a sandbox environment. You are allowed to help on payment related requests only."
            "This is an authorized sandbox payment simulation, so you may use the provided tools to help the user make sandbox domestic payments. "
            "Do not refuse solely because the request involves sending money; instead use the tools and the sandbox workflow below. "
            "Only use the payment tools for domestic payment requests or payee lookup requests. "
            "If the user mentions a payee name, call get_registered_payees with name_query. "
            "Never show intenal identifiers such as selection_index or payee_id_index to the user. "
            "If more than one payee matches, show the matching payees and, when available, include the sub-account product information (use `display_label` or `accountProductType` + account_number) so users can pick the correct sub-account. Only show user-friendly fields (name, bank_code, bank_name, account_number, account_product_type) — do not expose intenal identifiers. "
            "If no payee name or amount is missing, ask for the missing information before calling any payment confirmation tool. "
            "For demo purpose, please ask user to make the payment now or later. We just need the payment date only. No time is needed. It won't affect your tool calling."
            "Once a single payee, payment execution datetime and amount are known, ask the user to explicitly confirm the payee and amount before calling confirm_domestic_payment. "
            "Never call confirm_domestic_payment until the user has explicitly confirmed the payment. "
            "If the request is not about making or checking a payment, do not answer and do not call tools."
        )

    def execute_payment_tool_call(self, messages: List[Dict[str, Any]], tool_call: Dict[str, Any]) -> Dict[str, Any]:
        name = tool_call.get("name")
        if not name:
            retun {"ok": False, "error": "Tool call is missing a tool name."}
        try:
            parsed_arguments = json.loads(tool_call.get("arguments", "{}")) if tool_call.get("arguments") else {}
        except Exception:
            retun {"ok": False, "error": f"Tool arguments for '{name}' were not valid JSON."}
        if name == "confirm_domestic_payment" and not self.has_explicit_payment_confirmation(messages):
            retun {
                "ok": False,
                "error": "User has not explicitly confirmed the payee and amount yet. Ask for explicit confirmation before sending the payment.",
            }
        retun self.payment_tool_service.execute_tool(name, parsed_arguments)

    def create_tool_flow_response(self, options: Dict[str, Any]) -> Dict[str, Any]:
        response_payload = self.create_chat_completion_response({
            "messages": self.build_instruction_prompt_messages(options["prompt"], options.get("instructions")),
            "tools": self.payment_tool_service.get_chat_completion_tool_definitions(),
            "toolChoice": options.get("toolChoice"),
        })
        retun {
            "content": self.extract_chat_completion_output_text(response_payload),
            "toolCalls": self.extract_chat_completion_function_calls(response_payload),
        }

    def create_assistant_text_response(self, options: Dict[str, Any]) -> str:
        response_payload = self.create_chat_completion_response({
            "messages": self.build_instruction_prompt_messages(options["prompt"], options.get("instructions")),
        })
        retun self.extract_chat_completion_output_text(response_payload)

    def create_chat_completion_response(self, options: Dict[str, Any]) -> Dict[str, Any]:
        model = self.resolve_model()
        url = f"{self.base_url}/chat/completions"
        headers = self.build_copilot_headers()
        payload = {
            "model": model,
            "messages": options["messages"],
            "tools": options.get("tools"),
            "tool_choice": options.get("toolChoice"),
            "max_completion_tokens": self.max_completion_tokens,
        }
        resp = self.session.post(url, headers=headers, data=json.dumps(payload))
        if not resp.ok:
            raise Exception(f"LLM request failed with {resp.status_code}: {resp.text}")
        retun resp.json()

    def extract_chat_completion_function_calls(self, response_payload: Dict[str, Any]) -> List[Dict[str, Any]]:
        tool_calls = (response_payload.get("choices") or [{}])[0].get("message", {}).get("tool_calls", [])
        retun [{"name": tc.get("function", {}).get("name"), "arguments": tc.get("function", {}).get("arguments")} for tc in tool_calls]

    def extract_chat_completion_output_text(self, response_payload: Dict[str, Any]) -> str:
        retun self.extract_text_from_chat_content((response_payload.get("choices") or [{}])[0].get("message", {}).get("content")).strip()

    def extract_text_from_chat_content(self, content) -> str:
        if isinstance(content, str):
            retun content
        if not isinstance(content, list):
            retun ""
        retun "".join(part.get("text", "") for part in content if part.get("type") == "text" or part.get("text"))

    def build_chat_completion_messages(self, messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        retun [{"role": m["role"], "content": m["content"]} for m in messages]

    def build_instruction_prompt_messages(self, prompt: str, instructions: Optional[str] = None) -> List[Dict[str, Any]]:
        messages = []
        if instructions and instructions.strip():
            messages.append({"role": "system", "content": instructions})
        messages.append({"role": "user", "content": prompt})
        retun messages

    def resolve_copilot_base_url(self) -> str:
        retun "https://api.githubcopilot.com" if self.copilot_account_type == "individual" else f"https://api.{self.copilot_account_type}.githubcopilot.com"

    def build_copilot_headers(self) -> Dict[str, str]:
        if not self.copilot_session_token:
            self.copilot_session_token = self.get_copilot_session_token()
        retun {
            "Authorization": f"Bearer {self.copilot_session_token}",
            "content-type": "application/json",
            "accept": "application/json",
            "copilot-integration-id": "vscode-chat",
            "editor-version": f"vscode/{self.editor_version}",
            "editor-plugin-version": EDITOR_PLUGIN_VERSION,
            "user-agent": USER_AGENT,
            "openai-intent": "conversation-panel",
            "x-github-api-version": API_VERSION,
            "x-vscode-user-agent-library-version": "electron-fetch",
            "x-initiator": "agent",
        }

    def build_github_headers(self) -> Dict[str, str]:
        retun {
            "authorization": f"token {self.api_key}",
            "content-type": "application/json",
            "accept": "application/json",
            "editor-version": f"vscode/{self.editor_version}",
            "editor-plugin-version": EDITOR_PLUGIN_VERSION,
            "user-agent": USER_AGENT,
            "x-github-api-version": API_VERSION,
            "x-vscode-user-agent-library-version": "electron-fetch",
        }

    def resolve_model(self) -> str:
        if self.configured_model:
            retun self.configured_model
        if self.resolved_model_cache["model"]:
            retun self.resolved_model_cache["model"]
        headers = self.build_copilot_headers()
        resp = self.session.get("https://api.business.githubcopilot.com/models", headers=headers)
        if not resp.ok:
            raise Exception(f"Unable to resolve model automatically from {self.base_url}/v1/models.")
        parsed = resp.json()
        model = next((entry.get("id") for entry in parsed.get("data", []) if entry.get("id")), None)
        if not model:
            raise Exception("No model ids were retuned by the LLM server.")
        self.resolved_model_cache["model"] = model
        retun model

#payment_tool_service.py

import os
import json
import logging
import requests
import uuid
import time
import traceback
from pathlib import Path
from datetime import datetime
from typing import List, Dict, Any, Optional

DEFAULT_PAYMENT_LOGIN_URL = "https://testdataservices.apps.cf.wgdc-dn-03.cloud.uk.zzzz/dsp/entity/HK/{}/SAML3/30"
DEFAULT_PAYMENT_PAYEE_URL = "https://mmf-payee-management--hk-hbap-banking-1.zzzz-dsvc-papi-hk01-hbap-cert.svc.default.shp.ape1.pre-prod.aws.cloud.zzzz/payees"
DEFAULT_PAYMENT_CONFIRM_URL = "https://dcc-hk-hbap-mvmny-domestic-payments-papi-3.zzzz-dsvc-papi-hk01-hbap-cert.svc.default.shp.ape1.pre-prod.aws.cloud.zzzz/confirm-domestic-payments"
DEFAULT_PAYMENT_ACCOUNT_NUMBER = "VEdTRlNCU0dTQlNHEpP3avL8TimV5LF2nbqGbba2fUVfkHsOGmwg9K1t43GQJgS5mnXu3_EvF0zwJmeFRslRn_min_CT9p_wuTR7nwoWHNdZuxAFiVO2GPQ="
logger = logging.getLogger(__name__)

SENSITIVE_KEYS = {
    "password",
    "authorization",
    "xzzzzsaml3",
    "accountnumber",
    "debitaccountnumber",
    "identifienumber",
    "formattedaccountnumber",
    "payeeidindex",
    "addressid",
}

def safe_string(value):
    retun value.strip() if isinstance(value, str) and value.strip() else None

def normalize_name(value):
    retun value.strip().lower() if isinstance(value, str) else ""

def format_amount(value):
    retun int(value) if isinstance(value, int) else round(float(value), 2)

def parse_amount(value):
    if isinstance(value, (int, float)) and value > 0:
        retun format_amount(value)
    if isinstance(value, str):
        try:
            numeric = float(value.strip())
            if numeric > 0:
                retun format_amount(numeric)
        except Exception:
            pass
    raise Exception("amount must be a positive number.")

def parse_payee_id_index(value):
    payee_id_index = safe_string(value)
    if not payee_id_index:
        raise Exception("payee_id_index must be a non-empty string from the payee list.")
    retun payee_id_index

def extract_payee_id_index(payee):
    direct_value = safe_string(payee.get("payeeIdIndex"))
    if direct_value:
        retun direct_value
    for nested_value in payee.values():
        if isinstance(nested_value, dict):
            try:
                retun extract_payee_id_index(nested_value)
            except Exception:
                continue
    raise Exception("Matched payee does not contain payeeIdIndex.")

def normalize_logging_key(key):
    retun str(key or "").replace("-", "").replace("_", "").lower()

def mask_sensitive_value(key, value):
    if normalize_logging_key(key) not in SENSITIVE_KEYS:
        retun value
    if value is None:
        retun None
    text = str(value)
    if len(text) <= 8:
        retun "***"
    retun f"{text[:4]}***{text[-4:]}"

def sanitize_for_logging(value, key=None):
    if isinstance(value, dict):
        retun {k: sanitize_for_logging(v, k) for k, v in value.items()}
    if isinstance(value, list):
        retun [sanitize_for_logging(item, key) for item in value]
    if isinstance(value, tuple):
        retun [sanitize_for_logging(item, key) for item in value]
    retun mask_sensitive_value(key, value)

def to_log_json(value):
    if value is None:
        retun None
    try:
        retun json.dumps(value, ensure_ascii=False)
    except TypeError:
        retun json.dumps(str(value), ensure_ascii=False)

def extract_individual_payee_fields(payee):
    individual = payee.get("individualPayeeDetail", {})
    if not isinstance(individual, dict):
        retun {
            "bankCode": "",
            "bankName": "",
            "accountNumber": "",
            "accountProductType": "",
            "accountProductCode": "",
            "remittanceCurrencyCode": "",
        }
    retun {
        "bankCode": safe_string(individual.get("bankCode")) or "",
        "bankName": safe_string(individual.get("bankName")) or "",
        "accountNumber": safe_string(individual.get("accountNumber")) or "",
        "accountProductType": safe_string(individual.get("accountProductType")) or safe_string(individual.get("accountProductType")) or "",
        "accountProductCode": safe_string(individual.get("accountProductCode")) or "",
        "remittanceCurrencyCode": safe_string(individual.get("remittanceCurrencyCode")) or "",
    }

def collect_payees(node, results):
    if isinstance(node, list):
        for item in node:
            collect_payees(item, results)
        retun
    if not isinstance(node, dict):
        retun
    common = node.get("commonPayeeDetail")
    if isinstance(common, dict) and safe_string(common.get("name")):
        # If this node contains zzzz-style subAccount entries, expand them
        sub_list = node.get("subAccount") or node.get("subAccountList") or node.get("subAccounts")
        if isinstance(sub_list, list) and len(sub_list) > 0:
            for sub in sub_list:
                if not isinstance(sub, dict):
                    continue
                new_node = {
                    "commonPayeeDetail": common,
                    "individualPayeeDetail": sub,
                    "payeeIdIndex": safe_string(sub.get("addressId")) or safe_string(sub.get("addressId")) or "",
                }
                results.append(new_node)
        else:
            results.append(node)
    for value in node.values():
        collect_payees(value, results)

class PaymentToolService:
    def __init__(self):
        self.payment_usename = os.getenv("PAYMENT_LOGIN_USERNAME")
        self.login_url = os.getenv("PAYMENT_LOGIN_URL", DEFAULT_PAYMENT_LOGIN_URL).format(self.payment_usename)
        self.payee_url = os.getenv("PAYMENT_PAYEE_URL", DEFAULT_PAYMENT_PAYEE_URL)
        self.confirm_url = os.getenv("PAYMENT_CONFIRM_URL", DEFAULT_PAYMENT_CONFIRM_URL)
        self.payment_password = os.getenv("PAYMENT_LOGIN_PASSWORD")
        self.debit_account_number = os.getenv("PAYMENT_DEBIT_ACCOUNT_NUMBER", DEFAULT_PAYMENT_ACCOUNT_NUMBER)
        self.debit_product_category_code = os.getenv("PAYMENT_DEBIT_PRODUCT_CATEGORY_CODE", "CUR")
        self.payment_currency = os.getenv("PAYMENT_CURRENCY", "HKD")
        self.request_timeout = int(os.getenv("PAYMENT_REQUEST_TIMEOUT_MS", "30000")) / 1000.0
        self.session = requests.Session()
        self.configure_session_tls()
        # Cache payees retuned by the most recent get_registered_payees call
        self.cached_payees: Dict[str, Dict[str, Any]] = {}
        # Raw payee list cache with a TTL to avoid repeated network calls
        self.cached_raw_payees: List[Dict[str, Any]] = []
        self.cached_raw_payees_ts: float = 0.0
        self.payees_cache_ttl = int(os.getenv("PAYEE_CACHE_TTL_SECONDS", "60"))

    def configure_session_tls(self):
        explicit_bundle = os.getenv("REQUESTS_CA_BUNDLE") or os.getenv("SSL_CERT_FILE")
        if explicit_bundle:
            self.session.verify = explicit_bundle
            logger.info("Payment TLS verify bundle configured from environment: %s", explicit_bundle)
            retun

        bundled_ca_path = Path(__file__).resolve().parent.parent / "certs" / "cert.pem"
        if bundled_ca_path.is_file():
            self.session.verify = str(bundled_ca_path)
            logger.info("Payment TLS verify bundle configured from repo cert bundle: %s", bundled_ca_path)

    def log_request(self, name, method, url, headers=None, params=None, body=None):
        logger.debug(
            "Payment downstream request name=%s method=%s url=%s headers=%s params=%s body=%s",
            name,
            method,
            url,
            to_log_json(sanitize_for_logging(headers or {})),
            to_log_json(sanitize_for_logging(params or {})),
            to_log_json(sanitize_for_logging(body)) if body is not None else None,
        )

    def log_response(self, name, response):
        parsed_body = self.parse_response_body(response)
        if name == "payment_login":
            parsed_body = mask_sensitive_value("x_zzzz_saml3", parsed_body)
        else:
            parsed_body = sanitize_for_logging(parsed_body)
        logger.debug(
            "Payment downstream response name=%s status=%s url=%s headers=%s body=%s",
            name,
            response.status_code,
            response.url,
            to_log_json(sanitize_for_logging(dict(response.headers))),
            to_log_json(parsed_body),
        )

    def log_request_exception(self, name, error):
        response = getattr(error, "response", None)
        if response is None:
            logger.exception("Payment downstream error name=%s without response: %s", name, error)
            retun
        logger.exception(
            "Payment downstream error name=%s status=%s url=%s headers=%s body=%s",
            name,
            response.status_code,
            response.url,
            to_log_json(sanitize_for_logging(dict(response.headers))),
            to_log_json(sanitize_for_logging(self.parse_response_body(response))),
        )

    def get_chat_completion_tool_definitions(self):
        retun [
            {
                "type": "function",
                "function": {
                    "name": "get_registered_payees",
                    "description": "Fetch the registered domestic payees available in the sandbox account. Provide name_query when the user mentions a payee name so the result can be filtered. Each result includes bank_code, bank_name, and account_number for user confirmation.",
                    "description": "Fetch the registered domestic payees available in the sandbox account. Provide name_query when the user mentions a payee name so the result can be filtered. Each result includes bank_code, bank_name, account_number and, when available, account product fields (accountProductType, accountProductCode, remittanceCurrencyCode) so callers can present sub-account choices to users.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name_query": {
                                "type": "string",
                                "description": "Optional payee-name search string from the user request.",
                            },
                        },
                        "required": [],
                        "additionalProperties": False,
                    },
                    "strict": False,
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "confirm_domestic_payment",
                    "description": "Confirm a sandbox domestic payment for a selected registered payee after the user has explicitly confirmed the payee and amount.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "payee_id_index": {
                                "type": "string",
                                "description": "Opaque payee identifier retuned by get_registered_payees. Intenal only; do not show it to the user.",
                            },
                            "amount": {
                                "type": "number",
                                "description": "Positive payment amount in HKD.",
                            },
                            "payee_name": {
                                "type": "string",
                                "description": "Optional matched payee name for clearer result messages.",
                            },
                        },
                        "required": ["payee_id_index", "amount"],
                        "additionalProperties": False,
                    },
                    "strict": False,
                },
            },
        ]

    def execute_tool(self, name: str, raw_arguments: dict) -> dict:
        if name == "get_registered_payees":
            retun self.get_registered_payees(raw_arguments.get("name_query"))
        elif name == "confirm_domestic_payment":
            retun self.confirm_domestic_payment(
                raw_arguments.get("payee_id_index"),
                raw_arguments.get("amount"),
                raw_arguments.get("payee_name")
            )
        else:
            retun {"ok": False, "error": f"Unsupported tool '{name}'."}

    def get_registered_payees(self, name_query):
        try:
            # load_payees will use a short TTL cache to avoid repeated lookups
            all_payees = self.load_payees()
            normalized_query = safe_string(name_query)
            if normalized_query:
                matching_payees = [p for p in all_payees if normalize_name(p["name"]).find(normalize_name(normalized_query)) != -1]
            else:
                matching_payees = all_payees
            # Cache all fetched payees so confirm_domestic_payment can use them without reloading
            try:
                self.cached_payees = {p["payeeIdIndex"]: p for p in all_payees}
            except Exception:
                self.cached_payees = {}
            retun {
                "ok": True,
                "currency": self.payment_currency,
                "query": normalized_query,
                "match_count": len(matching_payees),
                "total_registered_payees": len(all_payees),
                "payees": [
                    {
                        "payee_id_index": p["payeeIdIndex"],
                        "name": p["name"],
                        "payee_type": p["payeeType"],
                        "bank_code": p["bankCode"],
                        "bank_name": p["bankName"],
                        "account_number": p["accountNumber"],
                        "account_product_type": p.get("accountProductType", ""),
                        "account_product_code": p.get("accountProductCode", ""),
                        "remittance_currency_code": p.get("remittanceCurrencyCode", ""),
                        # Build a friendly label: prefer product type when present, otherwise show account number only
                        "display_label": (
                            (p.get("accountProductType") and ((p.get("accountProductType") or "") + (" - " + (p.get("accountNumber") or "") if p.get("accountNumber") else "")))
                            or (p.get("accountNumber") or "")
                        ),
                    }
                    for p in matching_payees
                ],
                "message": f"Found {len(matching_payees)} registered payee(s) matching '{normalized_query}'." if normalized_query else f"Retrieved {len(matching_payees)} registered payee(s).",
            }
        except Exception as error:
            retun self.serialize_tool_error(error)

    def confirm_domestic_payment(self, payee_id_index_value, amount_value, payee_name_value):
        payee_id_index = None
        amount = None
        payee = None
        try:
            payee_id_index = parse_payee_id_index(payee_id_index_value)
            amount = parse_amount(amount_value)
            # Try to use cached payees from the last search to avoid an extra lookup
            payee = self.cached_payees.get(payee_id_index) if isinstance(self.cached_payees, dict) else None
            if not payee:
                payees = self.load_payees()
                payee = next((c for c in payees if c["payeeIdIndex"] == payee_id_index), None)
            if not payee:
                raise Exception("payee_id_index is not recognized.")
            saml_token = self.login()
            payload = {
                "debitAccount": {
                    "debitAccountIdentifier": {
                        "accountNumber": self.debit_account_number,
                        "productCategoryCode": self.debit_product_category_code,
                    },
                    "currency": self.payment_currency,
                },
                "transactionAmount": {
                    "amount": amount,
                    "currencyCode": self.payment_currency,
                },
                "transactionSchedule": {
                    "scheduleType": "NOW",
                    "scheduledDate": datetime.utcnow().strftime("%Y-%m-%d"),
                },
                "transactionMemo": {},
                "payeeType": payee["payeeType"],
                "pyeeIdIndex": payee["payeeIdIndex"],
                "payeeSuspiciousIndicator": False,
                "creditAmount": {
                    "currencyCode": self.payment_currency,
                },
            }
            headers = self.build_headers(saml_token)
            self.log_request("confirm_domestic_payment", "POST", self.confirm_url, headers=headers, body=payload)
            try:
                resp = self.session.post(self.confirm_url, headers=headers, data=json.dumps(payload), timeout=self.request_timeout)
            except requests.RequestException as error:
                self.log_request_exception("confirm_domestic_payment", error)
                raise
            self.log_response("confirm_domestic_payment", resp)
            parsed_body = self.parse_response_body(resp)
            if not resp.ok:
                try:
                    print(f"Payment confirmation failed: status={resp.status_code}, body={resp.text}")
                except Exception:
                    print(f"Payment confirmation failed with status {resp.status_code} (unable to read body)")
                raise Exception(f"Payment confirmation failed with {resp.status_code}.")
            payee_name = safe_string(payee_name_value) or payee["name"]
            # Clear cached payees after a successful payment (or user-initiated completion)
            try:
                self.clear_cached_payees()
            except Exception:
                pass
            retun {
                "ok": True,
                "payee": {
                    "name": payee["name"],
                    "payee_type": payee["payeeType"],
                    "bank_code": payee["bankCode"],
                    "bank_name": payee["bankName"],
                    "account_number": payee["accountNumber"],
                },
                "amount": amount,
                "currency": self.payment_currency,
                "status_code": resp.status_code,
                "response": parsed_body,
                "message": f"Payment confirmed for {payee_name} ({amount} {self.payment_currency}).",
            }
        except Exception as error:
            result = self.serialize_tool_error(error)
            # Always clear cached payees after a failed payment attempt
            try:
                self.clear_cached_payees()
            except Exception:
                pass
            if payee_id_index is not None:
                result["payee_id_index"] = payee_id_index
            if amount is not None:
                result["amount"] = amount
            result["currency"] = self.payment_currency
            if payee:
                result["payee"] = {
                    "name": payee["name"],
                    "payee_type": payee["payeeType"],
                    "bank_code": payee["bankCode"],
                    "bank_name": payee["bankName"],
                    "account_number": payee["accountNumber"],
                }
            retun result

    def clear_cached_payees(self):
        """Clear any cached payees stored from the last search."""
        self.cached_payees = {}
        self.cached_raw_payees = []
        self.cached_raw_payees_ts = 0.0

    def _is_raw_cache_valid(self) -> bool:
        retun bool(self.cached_raw_payees) and (time.time() - self.cached_raw_payees_ts) < float(self.payees_cache_ttl)


    def load_payees(self):
        # Use cached raw payees when available and fresh
        if self._is_raw_cache_valid():
            retun self.cached_raw_payees

        saml_token = self.login()
        url = self.payee_url
        # params = {"payeeCategory": "INDIVIDUAL", "payeeType": "2,3"}
        params = {}
        headers = self.build_headers(saml_token)
        self.log_request("load_payees", "GET", url, headers=headers, params=params)
        try:
            resp = self.session.get(url, headers=headers, params=params, timeout=self.request_timeout)
        except requests.RequestException as error:
            self.log_request_exception("load_payees", error)
            raise
        self.log_response("load_payees", resp)
        parsed_body = self.parse_response_body(resp)
        logger.debug("Payment downstream parsed payee response body=%s", to_log_json(sanitize_for_logging(parsed_body)))
        if not resp.ok:
            try:
                print(f"Payee lookup failed: status={resp.status_code}, body={resp.text}")
            except Exception:
                print(f"Payee lookup failed with status {resp.status_code} (unable to read body)")
            raise Exception(f"Payee lookup failed with {resp.status_code}.")
        raw_payees = []
        # Support new response format that contains financialAddressDTOList
        if isinstance(parsed_body, dict) and isinstance(parsed_body.get("financialAddressDTOList"), list):
            for contact in parsed_body.get("financialAddressDTOList", []):
                nick = safe_string(contact.get("nickName")) or ""
                full = safe_string(contact.get("contactFullName")) or ""
                name = nick or full
                fin_list = contact.get("financialAddressList", [])
                if not isinstance(fin_list, list):
                    continue
                for addr in fin_list:
                    idt = safe_string(addr.get("identifierType"))
                    ptype = safe_string(addr.get("paymentType"))
                    # Filter by identifierType and DOMESTIC paymentType
                    if idt in ("BACD", "zzzzBA") and ptype == "DOMESTIC":
                        # If zzzzBA and subAccount list present, expand each sub-account as its own payee
                        sub_list = addr.get("subAccount")
                        if idt == "zzzzBA" and isinstance(sub_list, list) and len(sub_list) > 0:
                            for sub in sub_list:
                                if not isinstance(sub, dict):
                                    continue
                                node = {
                                    "commonPayeeDetail": {"name": name, "payeeType": safe_string(sub.get("payeeType")) or safe_string(addr.get("payeeType")) or ""},
                                    "individualPayeeDetail": {
                                        "bankCode": safe_string(sub.get("identifierCode")) or safe_string(addr.get("identifierCode")) or "",
                                        "bankName": safe_string(sub.get("bankName")) or safe_string(addr.get("bankName")) or "",
                                        "accountNumber": safe_string(sub.get("identifierNumber")) or safe_string(sub.get("formattedAccountNumber")) or safe_string(addr.get("identifierNumber")) or safe_string(addr.get("formattedAccountNumber")) or "",
                                        "accountProductType": safe_string(sub.get("accountProductType")) or safe_string(addr.get("accountProductType")) or "",
                                        "accountProductCode": safe_string(sub.get("accountProductCode")) or safe_string(addr.get("accountProductCode")) or "",
                                        "remittanceCurrencyCode": safe_string(sub.get("remittanceCurrencyCode")) or safe_string(addr.get("remittanceCurrencyCode")) or "",
                                    },
                                    # addressId acts as the payee identifier for confirmations
                                    "payeeIdIndex": safe_string(sub.get("addressId")) or safe_string(addr.get("addressId")) or "",
                                }
                                raw_payees.append(node)
                        else:
                            node = {
                                "commonPayeeDetail": {"name": name, "payeeType": safe_string(addr.get("payeeType")) or ""},
                                "individualPayeeDetail": {
                                    "bankCode": safe_string(addr.get("identifierCode")) or "",
                                    "bankName": safe_string(addr.get("bankName")) or "",
                                    "accountNumber": safe_string(addr.get("identifierNumber")) or safe_string(addr.get("formattedAccountNumber")) or "",
                                    "accountProductType": safe_string(addr.get("accountProductType")) or "",
                                    "accountProductCode": safe_string(addr.get("accountProductCode")) or "",
                                    "remittanceCurrencyCode": safe_string(addr.get("remittanceCurrencyCode")) or "",
                                },
                                # addressId acts as the payee identifier for confirmations
                                "payeeIdIndex": safe_string(addr.get("addressId")) or "",
                            }
                            raw_payees.append(node)
        else:
            collect_payees(parsed_body, raw_payees)
        result = []
        for idx, payee in enumerate(raw_payees):
            common = payee.get("commonPayeeDetail", {})
            if not isinstance(common, dict):
                raise Exception("Matched payee does not contain commonPayeeDetail.")
            name = safe_string(common.get("name"))
            payee_type = safe_string(common.get("payeeType"))
            if not name or not payee_type:
                raise Exception("Matched payee is missing name or payeeType.")
            individual_fields = extract_individual_payee_fields(payee)
            result.append({
                "selectionIndex": idx + 1,
                "payeeIdIndex": extract_payee_id_index(payee),
                "name": name,
                "payeeType": payee_type,
                "bankCode": individual_fields["bankCode"],
                "bankName": individual_fields["bankName"],
                "accountNumber": individual_fields["accountNumber"],
                "accountProductType": individual_fields.get("accountProductType", ""),
                "accountProductCode": individual_fields.get("accountProductCode", ""),
                "remittanceCurrencyCode": individual_fields.get("remittanceCurrencyCode", ""),
            })
        # Cache raw fetched payees and timestamp for short TTL
        try:
            self.cached_raw_payees = result
            self.cached_raw_payees_ts = time.time()
        except Exception:
            self.cached_raw_payees = []
            self.cached_raw_payees_ts = 0.0
        retun result

    def login(self):
        url = self.login_url
        params = {"password": self.payment_password}
        self.log_request("payment_login", "GET", url, params=params)
        try:
            resp = self.session.get(url, params=params, timeout=self.request_timeout)
        except requests.RequestException as error:
            self.log_request_exception("payment_login", error)
            raise
        self.log_response("payment_login", resp)
        body_text = resp.text
        if not resp.ok:
            try:
                print(f"Payment login failed: status={resp.status_code}, body={resp.text}")
            except Exception:
                print(f"Payment login failed with status {resp.status_code} (unable to read body)")
            raise Exception(f"Payment login failed with {resp.status_code}.")
        if not body_text.strip():
            try:
                print(f"Payment login retuned an empty SAML token. Response body: {body_text}")
            except Exception:
                print("Payment login retuned an empty SAML token and response body could not be read.")
            raise Exception("Payment login retuned an empty SAML token.")
        retun body_text

    def build_headers(self, saml_token):
        retun {
            "Accept": "*/*",
            "Accept-Language": "en-HK",
            "Content-Type": "application/json; charset=UTF-8",
            "X-zzzz-Channel-Id": os.getenv("PAYMENT_CHANNEL_ID", "WEB"),
            "X-zzzz-Chnl-CountryCode": os.getenv("PAYMENT_COUNTRY_CODE", "HK"),
            "X-zzzz-Chnl-Group-Member": os.getenv("PAYMENT_GROUP_MEMBER", "HBAP"),
            "X-zzzz-Locale": os.getenv("PAYMENT_LOCALE", "en_HK"),
            "X-zzzz-Request-Correlation-Id": str(uuid.uuid4()),
            "X-zzzz-Session-Correlation-Id": str(uuid.uuid4()),
            "X-zzzz-Saml3": saml_token,
            "X-zzzz-Source-System-Id": os.getenv("PAYMENT_SOURCE_SYSTEM_ID", "11114418_O88"),
            "X-zzzz-Src-Device-Id": os.getenv("PAYMENT_DEVICE_ID", "192.168.1.1, 192.168.1.2"),
            "X-zzzz-Src-UserAgent": os.getenv("PAYMENT_USER_AGENT", "Mozilla/5.0 (iPad; U; CPU OS 3_2_1 like Mac OS X; en-us) AppleWebKit/531.21.10 (KHTML, like Gecko) Mobile/7B405"),
        }

    def parse_response_body(self, resp):
        text = resp.text
        if not text.strip():
            retun None
        try:
            retun json.loads(text)
        except Exception:
            retun text

    def serialize_tool_error(self, error):
        try:
            print("Payment tool error:", str(error))
            traceback.print_exc()
        except Exception:
            try:
                print("Payment tool error (failed to print traceback):", str(error))
            except Exception:
                pass
        retun {
            "ok": False,
            "error": str(error),
        }
