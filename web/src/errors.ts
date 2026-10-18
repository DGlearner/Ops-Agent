/** What to show the user; {@link ApiError}'s message is already in their language. */
export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
