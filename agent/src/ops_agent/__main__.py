"""ops-agent run: take tasks from Kafka until stopped. ops-agent analyse <incident_id>: analyse one incident now and
print the answer, without Kafka. ops-agent chat: the chat assistant's HTTP server. ops-agent eval-router: how well
the chat routes its labeled questions. ops-agent eval-chat: how long the chat takes on the live system."""

import argparse
import json
import logging
import signal
import threading
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from .analyst import Analyst
from .baseline import BaselineAnalyst
from .config import Settings
from .contracts import INCIDENT_ANALYSIS, AgentTask, Schemas
from .core_api import CoreApi
from .llm import OpenAIChat
from .worker import Worker


def build_analyst(settings: Settings, core: CoreApi):
    if settings.mode == "baseline":
        return BaselineAnalyst(core)
    settings.require_llm()
    model = OpenAIChat(settings.llm_base_url, settings.llm_api_key.get_secret_value(), settings.llm_model,
                       settings.llm_timeout_s, settings.llm_temperature)
    return Analyst(core, model, settings.max_tool_calls)


def chat_model(settings: Settings):
    from .chat.llm import OpenAIStreamingChat

    settings.require_llm()
    return OpenAIStreamingChat(settings.llm_base_url, settings.llm_api_key.get_secret_value(), settings.llm_model,
                               fast_model=settings.chat_fast_model, fast_extra_body=settings.chat_fast_extra_body,
                               thinking=settings.chat_thinking, timeout_s=settings.llm_timeout_s,
                               temperature=settings.llm_temperature)


def serve_chat(settings: Settings, core: CoreApi, host: str | None, port: int | None) -> None:
    import uvicorn

    from .chat.agent import ChatAgent
    from .chat.memory import ChatStore
    from .chat.server import create_app

    model = chat_model(settings) if settings.mode == "llm" else None
    store = ChatStore(settings.chat_db)
    agent = ChatAgent(core, store, model, max_tool_calls=settings.chat_max_tool_calls,
                      history_turns=settings.chat_history_turns, router_timeout_s=settings.chat_router_timeout_s,
                      prefetch=settings.chat_prefetch)
    logging.getLogger(__name__).info("chat on http://%s:%d, model %s, sessions in %s", host or settings.chat_host,
                                     port or settings.chat_port, model.name if model else "none (rules only)",
                                     settings.chat_db)
    uvicorn.run(create_app(agent, store), host=host or settings.chat_host, port=port or settings.chat_port,
                log_level="info", access_log=False)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="ops-agent", description="Ops-Agent's agent runtime")
    parser.add_argument("--mode", choices=["llm", "baseline"], help="overrides OPS_AGENT_MODE")
    commands = parser.add_subparsers(dest="command")
    commands.add_parser("run", help="take analysis tasks from Kafka until stopped (the default)")
    one = commands.add_parser("analyse", help="analyse one incident now and print the answer, without Kafka")
    one.add_argument("incident_id")
    chat = commands.add_parser("chat", help="serve the chat assistant over HTTP (SSE)")
    chat.add_argument("--host", help="overrides OPS_AGENT_CHAT_HOST (127.0.0.1)")
    chat.add_argument("--port", type=int, help="overrides OPS_AGENT_CHAT_PORT (8083)")
    commands.add_parser("eval-router", help="route the labeled questions in eval/ and report")
    timing = commands.add_parser("eval-chat", help="time the chat's answers on the live system, four ways")
    timing.add_argument("questions", nargs="+", help="asked in this order in one session")
    timing.add_argument("--runs", type=int, default=3)
    timing.add_argument("--expect", help="the component a right conclusion names, e.g. shop-mysql")
    timing.add_argument("--tz", default="Asia/Shanghai")
    timing.add_argument("--only", help='one variant, e.g. "thinking off, prefetch on"')
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    for noisy in ("httpx", "httpx2"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    settings = Settings()
    if args.mode:
        settings = settings.model_copy(update={"mode": args.mode})
    core = CoreApi(settings.core_url)
    if args.command == "chat":
        serve_chat(settings, core, args.host, args.port)
        return
    if args.command == "eval-chat":
        from .chat import latency_eval

        latency_eval.main(settings, args.questions, args.runs, args.expect, args.tz, args.only)
        return
    if args.command == "eval-router":
        from .chat import router_eval
        from .chat.router import Router

        model = chat_model(settings) if settings.mode == "llm" else None
        router_eval.main(Router(model, settings.chat_router_timeout_s) if model else None)
        return
    analyst = build_analyst(settings, core)
    schemas = Schemas(settings.contracts_dir)

    if args.command == "analyse":
        now = datetime.now(timezone.utc)
        task = AgentTask(task_id=uuid4(), kind=INCIDENT_ANALYSIS, incident_id=args.incident_id, requested_by="user",
                         requested_at=now, deadline=now + timedelta(minutes=10), language="zh")
        print(json.dumps(json.loads(schemas.result(analyst.analyse(task, task.deadline))), ensure_ascii=False,
                         indent=2))
        return

    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())
    Worker.connect(analyst, schemas, settings.kafka_bootstrap, settings.group_id,
                   settings.kafka_address_family).run(stop)


if __name__ == "__main__":
    main()
