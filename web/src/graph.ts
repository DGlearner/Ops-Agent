import type { Topology } from './api/types'

export interface Position {
  x: number
  y: number
}

const GAP_X = 180
const GAP_Y = 110

/**
 * Lays the dependency graph out top-down: callers above what they call, each node one row below its deepest
 * caller, so a database every service uses ends up at the bottom. Rows are sorted by name, so the picture does not
 * move between refreshes. A cycle stops the descent after as many rows as there are nodes.
 */
export function layout(topology: Topology): Map<string, Position> {
  const names = topology.nodes.map((n) => n.name)
  const known = new Set(names)
  const edges = topology.edges.filter((e) => known.has(e.from) && known.has(e.to) && e.from !== e.to)
  const row = new Map(names.map((n) => [n, 0]))
  for (let pass = 0; pass < names.length; pass++) {
    let changed = false
    for (const { from, to } of edges) {
      const below = Math.min((row.get(from) ?? 0) + 1, names.length - 1)
      if (below > (row.get(to) ?? 0)) {
        row.set(to, below)
        changed = true
      }
    }
    if (!changed) {
      break
    }
  }
  const rows = new Map<number, string[]>()
  for (const name of [...names].sort()) {
    const r = row.get(name) ?? 0
    rows.set(r, [...(rows.get(r) ?? []), name])
  }
  const positions = new Map<string, Position>()
  for (const [r, members] of rows) {
    members.forEach((name, i) => positions.set(name, { x: (i - (members.length - 1) / 2) * GAP_X, y: r * GAP_Y }))
  }
  return positions
}
