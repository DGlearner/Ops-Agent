"""The chat assistant (docs/project-design.md §6.7): a conversation about the system Ops-Agent watches. Each
question is routed (rules first, the model when the rules cannot tell), ops questions go through a fixed
orchestration that fetches the evidence before the model reasons, and everything streams to the console as SSE."""
