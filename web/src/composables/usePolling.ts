import { onBeforeUnmount, onMounted, type Ref } from 'vue'

/**
 * Runs {@code task} when the component mounts, then every {@code intervalMs} while {@code enabled} and the tab is
 * visible. A tick never overlaps a run in progress. {@code refresh} (e.g. after the user changed something) waits
 * for the run in progress, then runs once more, so it always sees the change.
 */
export function usePolling(task: () => Promise<void>, intervalMs: number, enabled?: Ref<boolean>) {
  let running: Promise<void> | undefined
  let again = false
  let timer: ReturnType<typeof setInterval> | undefined

  function refresh(): Promise<void> {
    if (running) {
      again = true
      return running
    }
    running = (async () => {
      try {
        do {
          again = false
          await task()
        } while (again)
      } finally {
        running = undefined
      }
    })()
    return running
  }

  onMounted(() => {
    void refresh()
    timer = setInterval(() => {
      if (!running && !document.hidden && (enabled?.value ?? true)) {
        void refresh()
      }
    }, intervalMs)
  })
  onBeforeUnmount(() => clearInterval(timer))

  return { refresh }
}
