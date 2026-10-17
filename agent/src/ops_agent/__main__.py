"""ops-agent run: take tasks from Kafka until stopped. ops-agent analyse <incident_id>: analyse one incident now and
print the answer, without Kafka."""

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


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="ops-agent", description="Ops-Agent's agent runtime")
    parser.add_argument("--mode", choices=["llm", "baseline"], help="overrides OPS_AGENT_MODE")
    commands = parser.add_subparsers(dest="command")
    commands.add_parser("run", help="take analysis tasks from Kafka until stopped (the default)")
    one = commands.add_parser("analyse", help="analyse one incident now and print the answer, without Kafka")
    one.add_argument("incident_id")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    settings = Settings()
    if args.mode:
        settings = settings.model_copy(update={"mode": args.mode})
    core = CoreApi(settings.core_url)
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
