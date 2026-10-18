<script setup lang="ts">
import { GraphChart, type GraphSeriesOption } from 'echarts/charts'
import {
  GridComponent,
  type GridComponentOption,
  TooltipComponent,
  type TooltipComponentOption,
} from 'echarts/components'
import { type ComposeOption, type ECharts, init, use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Topology } from '@/api/types'
import { layout } from '@/graph'

use([GraphChart, GridComponent, TooltipComponent, CanvasRenderer])

type Option = ComposeOption<GraphSeriesOption | GridComponentOption | TooltipComponentOption>

const props = withDefaults(
  defineProps<{
    topology: Topology
    /** Where the symptoms are: the services the alerts are about. */
    affected?: string[]
    /** What the agent names as the most likely cause. */
    suspected?: string[]
    /** A number shown after a node's name, e.g. its active incidents. */
    counts?: Record<string, number>
    selected?: string
    height?: number
  }>(),
  { affected: () => [], suspected: () => [], counts: () => ({}), selected: undefined, height: 300 },
)

const emit = defineEmits<{ select: [name: string] }>()

const COLOR = {
  normal: '#409eff',
  affected: '#f56c6c',
  suspected: '#8e44ad',
  selected: '#303133',
  edge: '#a8abb2',
  text: '#303133',
}

const container = ref<HTMLDivElement>()
let chart: ECharts | undefined
let observer: ResizeObserver | undefined

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}

interface TooltipParams {
  dataType?: string
  name?: string
  data?: { source?: string; target?: string }
}

function tooltip(params: unknown): string {
  const p = params as TooltipParams
  if (p.dataType === 'edge') {
    return `${escapeHtml(p.data?.source ?? '')} 依赖 ${escapeHtml(p.data?.target ?? '')}`
  }
  const node = props.topology.nodes.find((n) => n.name === p.name)
  if (!node) {
    return ''
  }
  const notes = [
    props.affected.includes(node.name) ? '出现症状' : '',
    props.suspected.includes(node.name) ? 'Agent 认为最可能的根因' : '',
  ].filter(Boolean)
  return [
    `<b>${escapeHtml(node.name)}</b>（${escapeHtml(node.kind)}）`,
    node.description ? escapeHtml(node.description) : '',
    notes.length ? notes.join('；') : '',
  ]
    .filter(Boolean)
    .join('<br/>')
}

/** An axis that spans the positions, with room around them; it is hidden and only places the nodes. */
function axis(values: number[], inverse: boolean) {
  const min = Math.min(...values, 0)
  const max = Math.max(...values, 0)
  const pad = max > min ? 0 : 1
  return { type: 'value' as const, show: false, inverse, min: min - pad, max: max + pad }
}

function option(): Option {
  const positions = layout(props.topology)
  const points = [...positions.values()]
  // On a grid rather than layout 'none': that stretches the view to fit and squashes the symbols with it.
  return {
    tooltip: { formatter: tooltip },
    grid: { left: 90, right: 90, top: 30, bottom: 50 },
    xAxis: axis(
      points.map((p) => p.x),
      false,
    ),
    yAxis: axis(
      points.map((p) => p.y),
      true,
    ),
    series: [
      {
        type: 'graph',
        layout: 'none',
        coordinateSystem: 'cartesian2d',
        animation: false,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 9,
        lineStyle: { color: COLOR.edge, width: 1.5 },
        label: { show: true, position: 'bottom', color: COLOR.text, fontSize: 13 },
        emphasis: { scale: false, lineStyle: { width: 2.5 } },
        data: props.topology.nodes.map((node) => {
          const position = positions.get(node.name) ?? { x: 0, y: 0 }
          const affected = props.affected.includes(node.name)
          const suspected = props.suspected.includes(node.name)
          const count = props.counts[node.name]
          return {
            name: node.name,
            value: [position.x, position.y],
            symbol: node.kind === 'service' ? 'circle' : 'roundRect',
            symbolSize: node.kind === 'service' ? 42 : [56, 36],
            itemStyle: {
              color: affected ? COLOR.affected : COLOR.normal,
              borderColor: suspected ? COLOR.suspected : node.name === props.selected ? COLOR.selected : '#fff',
              borderWidth: suspected ? 6 : node.name === props.selected ? 3 : 2,
            },
            label: {
              formatter: count ? `${node.name}（${count}）` : node.name,
              fontWeight: affected || suspected ? 'bold' : 'normal',
            },
          }
        }),
        links: props.topology.edges.map((e) => ({ source: e.from, target: e.to })),
      },
    ],
  }
}

onMounted(() => {
  if (!container.value) {
    return
  }
  chart = init(container.value)
  chart.setOption(option())
  chart.on('click', (params) => {
    if (params.dataType === 'node' && params.name) {
      emit('select', params.name)
    }
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(container.value)
})

watch(
  () => [props.topology, props.affected, props.suspected, props.counts, props.selected],
  () => chart?.setOption(option(), true),
  { deep: true },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div>
    <div ref="container" :style="{ height: `${height}px` }" />
    <div class="legend">
      <span><i class="dot normal" />正常</span>
      <span><i class="dot affected" />出现症状</span>
      <span><i class="dot suspected" />Agent 认为最可能的根因</span>
      <span class="muted">箭头：依赖方向；方块：数据库等基础组件</span>
    </div>
  </div>
</template>

<style scoped>
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 8px;
  font-size: 12px;
}

.legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #409eff;
}

.dot.affected {
  background: #f56c6c;
}

.dot.suspected {
  background: #409eff;
  box-shadow: 0 0 0 3px #8e44ad;
}
</style>
