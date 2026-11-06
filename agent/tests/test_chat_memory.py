"""The session's memory under concurrent writers: a summary is written only over the state it was made from."""

import asyncio

import pytest

from chat_support import ChatCore, Script, ScriptedStreamingModel, collect
from ops_agent.chat.agent import ChatAgent
from ops_agent.chat.memory import ChatStore


@pytest.fixture
def store(tmp_path):
    s = ChatStore(tmp_path / "chat.sqlite3")
    yield s
    s.close()


def test_a_summary_is_only_written_over_the_state_it_was_made_from(store):
    session = store.create_session()["session_id"]

    assert store.save_summary(session, "turns 1-12", through=12, expected_through=0)
    # A slower summary, started from the same state, lands after it: dropped, not written over it.
    assert not store.save_summary(session, "turns 1-8", through=8, expected_through=0)

    saved = store.session(session)
    assert (saved["summary"], saved["summarized_through"]) == ("turns 1-12", 12)


class SlowSummaries(ScriptedStreamingModel):
    """The quick model holds each summary until released."""

    def __init__(self, *turns, quick):
        super().__init__(*turns, quick=quick)
        self.release = asyncio.Event()
        self.summarizing = asyncio.Event()

    async def quick(self, messages, **kw):
        self.summarizing.set()
        await self.release.wait()
        return await super().quick(messages, **kw)


async def test_a_summary_overtaken_by_another_writer_is_dropped(store):
    model = SlowSummaries(*[Script(content=f"回答 {n} [I1]") for n in range(8)], quick=["旧的摘要"])
    chat = ChatAgent(ChatCore().api(), store, model, history_turns=2)
    session = store.create_session()["session_id"]
    for n in range(6):
        await collect(chat.ask(session, f"刚才下单为什么失败？第 {n} 次"))
    await model.summarizing.wait()

    # Another worker on the same file folds the conversation in first.
    assert store.save_summary(session, "另一个进程的摘要", through=10, expected_through=0)
    model.release.set()
    await asyncio.gather(*chat._background)

    saved = store.session(session)
    assert (saved["summary"], saved["summarized_through"]) == ("另一个进程的摘要", 10)


async def test_one_summary_at_a_time_per_session(store):
    model = SlowSummaries(*[Script(content=f"回答 {n} [I1]") for n in range(9)], quick=["摘要"])
    chat = ChatAgent(ChatCore().api(), store, model, history_turns=2)
    session = store.create_session()["session_id"]
    for n in range(7):
        await collect(chat.ask(session, f"刚才下单为什么失败？第 {n} 次"))

    # The seventh answer found a summary still running and did not start a second one over the same messages.
    assert len(chat._background) == 1
    model.release.set()
    await asyncio.gather(*chat._background)
    assert store.session(session)["summarized_through"] == 8
