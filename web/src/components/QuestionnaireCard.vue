<script setup lang="ts">
// The questions an answer ended with (agent/src/ops_agent/chat/clarify.py). Answering sends them as the next
// message; the answer goes on from where it stopped. Only the session's open questionnaire can be answered.
import { computed, reactive } from 'vue'
import type { Questionnaire, QuestionAnswer } from '@/api/chat'
import { filledAnswers } from '@/chat'

const props = defineProps<{ questionnaire: Questionnaire; active: boolean; busy?: boolean }>()
const emit = defineEmits<{ answer: [answers: QuestionAnswer[]] }>()

const answers = reactive<QuestionAnswer[]>(props.questionnaire.questions.map((q) => ({ id: q.id, choices: [], text: '' })))
const ready = computed(() => filledAnswers(answers).length > 0)

function single(i: number, value: string) {
  answers[i]!.choices = [value]
}

function submit() {
  if (ready.value) {
    emit('answer', filledAnswers(answers))
  }
}
</script>

<template>
  <div class="questionnaire" :class="{ inactive: !active }">
    <div v-for="(q, i) in questionnaire.questions" :key="q.id" class="question">
      <div class="title">{{ q.title }}</div>
      <template v-if="q.options.length">
        <el-radio-group
          v-if="q.kind === 'single'"
          :model-value="answers[i]!.choices[0]"
          :disabled="!active"
          class="options"
          @change="(v) => single(i, String(v))"
        >
          <el-radio v-for="o in q.options" :key="o.id" :value="o.id" border>
            {{ o.label }}<span v-if="o.detail" class="muted detail">{{ o.detail }}</span>
          </el-radio>
        </el-radio-group>
        <el-checkbox-group v-else v-model="answers[i]!.choices" :disabled="!active" class="options">
          <el-checkbox v-for="o in q.options" :key="o.id" :value="o.id" border>{{ o.label }}</el-checkbox>
        </el-checkbox-group>
      </template>
      <el-input
        v-if="q.allow_text"
        v-model="answers[i]!.text"
        :disabled="!active"
        size="small"
        maxlength="200"
        :placeholder="q.options.length ? '或者自己写' : '你的回答'"
        class="text"
      />
    </div>
    <div v-if="active" class="buttons">
      <el-button type="primary" size="small" :disabled="!ready" :loading="busy" @click="submit">回答并继续</el-button>
      <span class="muted">也可以直接问别的问题，这组问题就作废</span>
    </div>
    <div v-else class="muted">这组问题已经回答过，或者已被新的问题取代</div>
  </div>
</template>

<style scoped>
.questionnaire {
  border: 1px solid var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
  padding: 10px 12px;
  margin-top: 10px;
  font-size: 13px;
}

.questionnaire.inactive {
  background: var(--el-fill-color-lighter);
  border-color: var(--el-border-color);
}

.question + .question {
  margin-top: 10px;
}

.title {
  font-weight: 500;
  margin-bottom: 6px;
}

.options {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.options :deep(.el-radio),
.options :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
  min-height: 32px;
  padding: 4px 12px;
  white-space: normal;
}

.detail {
  margin-left: 8px;
  font-size: 12px;
}

.text {
  margin-top: 6px;
  max-width: 420px;
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
</style>
