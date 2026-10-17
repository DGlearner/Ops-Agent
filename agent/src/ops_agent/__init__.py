"""Ops-Agent's agent runtime (docs/project-design.md §7.6).

Takes incident analysis tasks from Kafka, looks at the incident through core-service's read-only API, and answers
with a structured, evidence-backed attribution. It reads; it never changes anything.
"""
