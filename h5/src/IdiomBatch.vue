<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { adminRequest, AdminRequestError } from "./adminApi";

interface BatchDraft {
  token: string;
  preview: {
    skipped: number;
    pendingEntryIds: number[];
    levels: {
      title: string;
      preview: {
        levelNo: number;
        free: boolean;
        questions: {
          entryId: number;
          term: string;
          prompt: string;
          pinyin: string;
          meaning: string;
        }[];
        tiles: { character: string }[];
      };
    }[];
  };
}

const emit = defineEmits<{ sessionExpired: []; published: []; reviewed: [] }>();
const difficulty = ref(2);
const levelCount = ref(10);
const approvedOnly = ref(false);
const exclusions = ref<number[]>([]);
const shuffleSeed = ref<number>();
const draft = ref<BatchDraft>();
const review = ref<{ entryId: number; pinyin: string; meaning: string }>();
const pending = computed(() => new Set(draft.value?.preview.pendingEntryIds));
const suggestions = ref<
  Record<number, { pinyin: string; meaning: string; selected: boolean }>
>({});
const questions = computed(
  () =>
    draft.value?.preview.levels.flatMap((level) => level.preview.questions) ??
    [],
);
const missing = computed(() =>
  questions.value.filter(
    (question) =>
      pending.value.has(question.entryId) &&
      !question.meaning.trim() &&
      !suggestions.value[question.entryId],
  ),
);
const suggestedReviews = computed(() =>
  questions.value.flatMap((question) => {
    const suggestion = suggestions.value[question.entryId];
    return suggestion && pending.value.has(question.entryId)
      ? [{ question, suggestion }]
      : [];
  }),
);
const selectedReviews = computed(() =>
  suggestedReviews.value.filter(({ suggestion }) => suggestion.selected),
);
const generating = ref(false);
const generatedCount = ref(0);
const generationTotal = ref(0);
const busy = ref(false);
const error = ref("");
const message = ref("");

watch([difficulty, levelCount, approvedOnly], () => {
  draft.value = undefined;
  review.value = undefined;
  suggestions.value = {};
  exclusions.value = [];
  error.value = "";
  message.value = "";
});

async function perform(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await action();
  } catch (cause) {
    if (cause instanceof AdminRequestError && cause.status === 401) {
      emit("sessionExpired");
    } else {
      error.value = (cause as Error).message;
    }
  } finally {
    busy.value = false;
  }
}

async function loadPreview(
  excludeEntryIds: number[],
  seed = shuffleSeed.value,
) {
  draft.value = await adminRequest<BatchDraft>("/levels/batch/preview", {
    difficulty: difficulty.value,
    levelCount: levelCount.value,
    excludeEntryIds,
    approvedOnly: approvedOnly.value,
    shuffleSeed: seed,
  });
  exclusions.value = excludeEntryIds;
  shuffleSeed.value = seed;
  review.value = undefined;
  for (const key of Object.keys(suggestions.value)) {
    if (!pending.value.has(Number(key))) delete suggestions.value[Number(key)];
  }
}

async function generateMeanings() {
  const current = draft.value;
  const targets = [...missing.value];
  if (!current || !targets.length) return;
  await perform(async () => {
    generating.value = true;
    generatedCount.value = 0;
    generationTotal.value = targets.length;
    let received = 0;
    try {
      for (let offset = 0; offset < targets.length; offset += 16) {
        const chunk = targets.slice(offset, offset + 16);
        const result = await adminRequest<Record<number, string>>(
          "/levels/batch/meanings",
          {
            token: current.token,
            entryIds: chunk.map((question) => question.entryId),
          },
        );
        for (const question of chunk) {
          const meaning = result[question.entryId];
          if (meaning) {
            suggestions.value[question.entryId] = {
              pinyin: question.pinyin,
              meaning,
              selected: true,
            };
            received++;
          }
        }
        generatedCount.value += chunk.length;
      }
      message.value = `已生成 ${received} 条释义草稿。请核对后确认。`;
      if (received < targets.length) {
        message.value += `还有 ${targets.length - received} 条未生成，可以重试或手动填写。`;
      }
    } finally {
      generating.value = false;
    }
  });
}

async function confirmMeanings() {
  const current = draft.value;
  if (!current || !selectedReviews.value.length) return;
  const entries = selectedReviews.value.map(({ question, suggestion }) => ({
    entryId: question.entryId,
    pinyin: suggestion.pinyin.trim(),
    meaning: suggestion.meaning.trim(),
    difficulty: difficulty.value,
  }));
  await perform(async () => {
    await adminRequest<void>("/levels/batch/review", {
      token: current.token,
      entries,
    });
    emit("reviewed");
    await loadPreview(exclusions.value);
    message.value = `已确认并保存 ${entries.length} 条释义。`;
  });
}

async function generate(excludeEntryIds = exclusions.value) {
  await perform(() => loadPreview(excludeEntryIds));
}

async function generateNewOrder() {
  const seed = Math.floor(Math.random() * 2 ** 32);
  await perform(() => loadPreview(exclusions.value, seed));
}

async function saveReview() {
  const input = review.value;
  if (!input) return;
  await perform(async () => {
    await adminRequest<void>("/idioms/review", {
      ...input,
      difficulty: difficulty.value,
    });
    emit("reviewed");
    await loadPreview(exclusions.value);
  });
}

function replace(entryId: number) {
  void generate([...exclusions.value, entryId]);
}

async function publish() {
  const current = draft.value;
  if (!current?.preview.levels.length || pending.value.size) return;
  await perform(async () => {
    const levels = await adminRequest<{ levelNo: number }[]>(
      "/levels/batch/publish",
      { token: current.token },
    );
    message.value = `已发布 ${levels.length} 关（第 ${levels[0]?.levelNo}～${levels.at(-1)?.levelNo} 关）`;
    draft.value = undefined;
    suggestions.value = {};
    exclusions.value = [];
    emit("published");
  });
}
</script>

<template>
  <details class="panel batch-panel">
    <summary>批量生成关卡</summary>
    <p>
      从同一难度选取尚未使用的成语，每 3
      道题组成一关。待审核词也可以先生成草稿。
    </p>
    <form class="batch-controls" @submit.prevent="generateNewOrder">
      <label
        >难度
        <select v-model="difficulty" :disabled="busy">
          <option
            v-for="(label, index) in ['入门', '基础', '进阶', '挑战']"
            :key="label"
            :value="index + 1"
          >
            {{ label }}
          </option>
        </select>
      </label>
      <label
        >生成关数
        <input
          v-model.number="levelCount"
          :disabled="busy"
          type="number"
          min="1"
          max="100"
          required
        />
      </label>
      <label
        >词条范围
        <select v-model="approvedOnly" :disabled="busy">
          <option :value="false">含待审核词条</option>
          <option :value="true">仅已审核词条</option>
        </select>
      </label>
      <button class="primary" :disabled="busy" type="submit">生成预览</button>
      <button
        v-if="exclusions.length"
        :disabled="busy"
        type="button"
        @click="generate([])"
      >
        恢复已排除的词条
      </button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <div v-if="draft">
      <p role="status">
        已生成 {{ draft.preview.levels.length }} / {{ levelCount }} 关。
        <template v-if="draft.preview.levels.length < levelCount"
          >可用词条不足时，只生成能凑齐三题的关卡。</template
        >
        <template v-if="draft.preview.skipped"
          >本次检查跳过 {{ draft.preview.skipped }} 条。</template
        >
      </p>
      <p v-if="pending.size">
        还有
        {{ pending.size }}
        条成语需要核对拼音和儿童版释义。审核完成后可发布这批关卡。
      </p>
      <div v-if="missing.length || generating" class="row-actions">
        <button type="button" :disabled="busy" @click="generateMeanings">
          {{
            generating
              ? "正在生成释义…"
              : `生成儿童版释义（${missing.length} 条）`
          }}
        </button>
        <span v-if="generating" role="status"
          >已处理 {{ generatedCount }} / {{ generationTotal }} 条</span
        >
      </div>
      <section
        v-if="generationTotal > 0 && missing.length && !generating"
        class="preview"
        aria-label="未生成释义的词条"
      >
        <h3>未生成释义（{{ missing.length }} 条）</h3>
        <ul class="batch-levels">
          <li v-for="question in missing" :key="question.entryId">
            <strong>{{ question.term }}</strong>
            · 第
            {{
              draft.preview.levels.find((level) =>
                level.preview.questions.some(
                  (item) => item.entryId === question.entryId,
                ),
              )?.preview.levelNo
            }}
            关
            <form
              v-if="review?.entryId === question.entryId"
              class="batch-review"
              @submit.prevent="saveReview"
            >
              <label>
                完整拼音
                <input
                  v-model="review.pinyin"
                  :disabled="busy"
                  maxlength="255"
                  required
                />
              </label>
              <label>
                儿童版释义
                <textarea
                  v-model="review.meaning"
                  :disabled="busy"
                  rows="2"
                  maxlength="255"
                  required
                />
              </label>
              <div class="row-actions">
                <button class="primary" :disabled="busy" type="submit">
                  保存审核
                </button>
                <button
                  type="button"
                  :disabled="busy"
                  @click="review = undefined"
                >
                  取消
                </button>
              </div>
            </form>
            <div v-else class="row-actions">
              <button
                type="button"
                :disabled="busy"
                @click="
                  review = {
                    entryId: question.entryId,
                    pinyin: question.pinyin,
                    meaning: question.meaning,
                  }
                "
              >
                手动填写
              </button>
              <button
                type="button"
                :disabled="busy || exclusions.length >= 1000"
                @click="replace(question.entryId)"
              >
                排除此词并重新组关
              </button>
            </div>
          </li>
        </ul>
      </section>
      <section v-if="suggestedReviews.length" class="preview">
        <h3>核对释义草稿</h3>
        <div class="batch-levels">
          <div
            v-for="{ question, suggestion } in suggestedReviews"
            :key="question.entryId"
            class="batch-review"
          >
            <label class="checkbox-label">
              <input
                v-model="suggestion.selected"
                type="checkbox"
                :disabled="busy"
              />
              {{ question.term }}
            </label>
            <label
              >完整拼音<input
                v-model="suggestion.pinyin"
                :disabled="busy"
                maxlength="255"
            /></label>
            <label
              >儿童版释义<textarea
                v-model="suggestion.meaning"
                :disabled="busy"
                rows="2"
                maxlength="255"
              />
            </label>
          </div>
        </div>
        <button
          class="primary"
          type="button"
          :disabled="
            busy ||
            !selectedReviews.length ||
            selectedReviews.some(
              ({ suggestion }) =>
                !suggestion.pinyin.trim() || !suggestion.meaning.trim(),
            )
          "
          @click="confirmMeanings"
        >
          确认审核 {{ selectedReviews.length }} 条
        </button>
      </section>
      <div v-if="draft.preview.levels.length" class="batch-levels">
        <details
          v-for="level in draft.preview.levels"
          :key="level.preview.levelNo"
          class="preview"
        >
          <summary>
            第 {{ level.preview.levelNo }} 关 ·
            {{ level.preview.free ? "免费" : "需解锁" }}
          </summary>
          <ul>
            <li
              v-for="question in level.preview.questions"
              :key="question.entryId"
            >
              <strong>{{ question.prompt }}</strong> · {{ question.term }} ·
              {{ question.pinyin }}
              <small>{{ question.meaning || "待补儿童版释义" }}</small>
              <form
                v-if="
                  review?.entryId === question.entryId &&
                  !(
                    generationTotal > 0 &&
                    missing.some((item) => item.entryId === question.entryId)
                  )
                "
                class="batch-review"
                @submit.prevent="saveReview"
              >
                <label
                  >完整拼音<input
                    v-model="review.pinyin"
                    :disabled="busy"
                    maxlength="255"
                    required
                /></label>
                <label
                  >儿童版释义<textarea
                    v-model="review.meaning"
                    :disabled="busy"
                    rows="2"
                    maxlength="255"
                    required
                  />
                </label>
                <div class="row-actions">
                  <button class="primary" :disabled="busy" type="submit">
                    保存审核
                  </button>
                  <button
                    type="button"
                    :disabled="busy"
                    @click="review = undefined"
                  >
                    取消
                  </button>
                </div>
              </form>
              <button
                v-else-if="pending.has(question.entryId)"
                type="button"
                :disabled="busy"
                @click="
                  review = {
                    entryId: question.entryId,
                    pinyin: question.pinyin,
                    meaning: question.meaning,
                  }
                "
              >
                审核词条
              </button>
              <button
                type="button"
                :disabled="busy || exclusions.length >= 1000"
                @click="replace(question.entryId)"
              >
                排除此词并重新组关
              </button>
            </li>
          </ul>
          <p class="tile-list">
            <span v-for="(tile, index) in level.preview.tiles" :key="index">{{
              tile.character
            }}</span>
          </p>
        </details>
      </div>
      <button
        v-if="draft.preview.levels.length"
        class="primary"
        :disabled="busy || pending.size > 0"
        @click="publish"
      >
        发布这 {{ draft.preview.levels.length }} 关
      </button>
    </div>
  </details>
</template>
