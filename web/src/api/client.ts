import type { Alert, Analysis, Incident, IncidentStatus, Page, Problem, Severity, TimelineEntry, Topology } from './types'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message)
  }
}

export interface IncidentFilter {
  status?: IncidentStatus
  severity?: Severity
  service?: string
  limit?: number
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
    })
  } catch {
    throw new ApiError(0, undefined, '连不上服务，请检查 core-service 和本页面的服务是否在运行')
  }
  if (!response.ok) {
    let problem: Partial<Problem> = {}
    try {
      problem = (await response.json()) as Partial<Problem>
    } catch {
      // Not Problem Details: the dev server answers on its own when it cannot reach core-service.
    }
    throw new ApiError(response.status, problem.code, problem.detail ?? `core-service 返回 HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const api = {
  /** Newest first. */
  incidents(filter: IncidentFilter, cursor?: string): Promise<Page<Incident>> {
    return request(`/incidents${query({ ...filter, cursor })}`)
  },
  incident(id: string): Promise<Incident> {
    return request(`/incidents/${id}`)
  },
  incidentAlerts(id: string): Promise<Alert[]> {
    return request(`/incidents/${id}/alerts`)
  },
  timeline(id: string): Promise<TimelineEntry[]> {
    return request(`/incidents/${id}/timeline`)
  },
  transition(id: string, toStatus: IncidentStatus, note?: string): Promise<Incident> {
    return request(`/incidents/${id}/transitions`, {
      method: 'POST',
      body: JSON.stringify({ to_status: toStatus, note: note || undefined }),
    })
  },
  /** Newest first. */
  analyses(incidentId: string): Promise<Analysis[]> {
    return request(`/incidents/${incidentId}/analyses`)
  },
  /** Answers at once with the pending analysis, or with the one already pending. */
  requestAnalysis(incidentId: string): Promise<Analysis> {
    return request(`/incidents/${incidentId}/analyses`, { method: 'POST' })
  },
  topology(): Promise<Topology> {
    return request('/topology')
  },
}
