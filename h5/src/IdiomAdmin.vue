<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import IdiomBatch from "./IdiomBatch.vue";
import IdiomPublishedLevels from "./IdiomPublishedLevels.vue";
import { adminRequest, AdminRequestError } from "./adminApi";

const emit = defineEmits<{ sessionExpired: [] }>();
defineProps<{ canPublish: boolean }>();

interface Entry {
  id: number;
  term: string;
  sourcePinyin: string | null;
  reviewedPinyin: string | null;
  childMeaning: string | null;
  difficulty: number | null;
  reviewStatus: string;
}
interface QuestionDraft {
  entryId: number;
  term: string;
  pinyin: string;
  meaning: string;
  difficulty: number;
  blankIndex: number;
}
interface Preview {
  levelNo: number;
  free: boolean;
  questions: {
    entryId: number;
    prompt: string;
    promptPinyin: string;
    answer: string;
  }[];
  tiles: { character: string; pinyin: string }[];
}

const difficultyLabels = ["入门", "基础", "进阶", "挑战"];
const keyword = ref("");
const difficulty = ref("");
const page = ref(1);
const pageSize = ref(10);
const total = ref<number>();
const tableScroll = ref<HTMLElement>();
const entries = ref<Entry[]>([]);
const hasMore = ref(false);
const loading = ref(false);
const searchError = ref("");
const draftError = ref("");
const title = ref("");
const questions = ref<QuestionDraft[]>([]);
const preview = ref<Preview>();
const working = ref(false);
const published = ref("");
const publishedRevision = ref(0);
let appliedFilters = { keyword: "", difficulty: "" };
const totalPages = computed(() =>
  Math.ceil((total.value ?? 0) / pageSize.value),
);

const draft = computed(() => ({
  title: title.value.trim(),
  questions: questions.value.map(
    ({ entryId, pinyin, meaning, difficulty, blankIndex }) => ({
      entryId,
      pinyin: pinyin.trim(),
      meaning: meaning.trim(),
      difficulty,
      blankIndex,
    }),
  ),
}));
const ready = computed(
  () =>
    draft.value.title &&
    draft.value.questions.length === 3 &&
    draft.value.questions.every(
      (question) => question.pinyin && question.meaning,
    ),
);
watch(
  draft,
  () => {
    preview.value = undefined;
  },
  { flush: "sync" },
);

async function request<T>(path: string, body?: object): Promise<T> {
  try {
    return await adminRequest<T>(path, body);
  } catch (error) {
    if (error instanceof AdminRequestError && error.status === 401) {
      emit("sessionExpired");
    }
    throw error;
  }
}

async function loadPage(number: number) {
  if (loading.value) return;
  loading.value = true;
  searchError.value = "";
  const query = new URLSearchParams({
    keyword: appliedFilters.keyword,
    page: String(number),
    pageSize: String(pageSize.value),
  });
  if (appliedFilters.difficulty)
    query.set("difficulty", appliedFilters.difficulty);
  try {
    const result = await request<{
      entries: Entry[];
      hasMore: boolean;
      total?: number;
    }>("/idioms?" + query);
    entries.value = result.entries;
    page.value = number;
    total.value = result.total;
    hasMore.value = result.hasMore;
    if (tableScroll.value) tableScroll.value.scrollTop = 0;
  } catch (error) {
    searchError.value = (error as Error).message;
  } finally {
    loading.value = false;
  }
}

function search() {
  appliedFilters = {
    keyword: keyword.value.trim(),
    difficulty: difficulty.value,
  };
  void loadPage(1);
}

function select(entry: Entry) {
  if (
    questions.value.length === 3 ||
    questions.value.some((question) => question.entryId === entry.id)
  )
    return;
  published.value = "";
  questions.value.push({
    entryId: entry.id,
    term: entry.term,
    pinyin: entry.reviewedPinyin ?? entry.sourcePinyin ?? "",
    meaning: entry.childMeaning ?? "",
    difficulty: entry.difficulty ?? 2,
    blankIndex: 0,
  });
}

async function previewLevel() {
  working.value = true;
  draftError.value = "";
  preview.value = undefined;
  try {
    preview.value = await request<Preview>("/levels/preview", draft.value);
  } catch (error) {
    draftError.value = (error as Error).message;
  } finally {
    working.value = false;
  }
}

async function approveEntry(question: QuestionDraft) {
  working.value = true;
  draftError.value = "";
  published.value = "";
  try {
    await request<void>("/idioms/review", {
      entryId: question.entryId,
      pinyin: question.pinyin.trim(),
      meaning: question.meaning.trim(),
      difficulty: question.difficulty,
    });
    published.value = `「${question.term}」审核已保存，可用于批量组关`;
    void loadPage(page.value);
  } catch (error) {
    draftError.value = (error as Error).message;
  } finally {
    working.value = false;
  }
}

async function publishLevel() {
  if (!preview.value) return;
  working.value = true;
  draftError.value = "";
  try {
    const result = await request<{ levelNo: number; title: string }>(
      "/levels?expectedLevelNo=" + preview.value.levelNo,
      draft.value,
    );
    published.value = `第 ${result.levelNo} 关「${result.title}」已发布`;
    title.value = "";
    questions.value = [];
    refreshAfterPublish();
  } catch (error) {
    draftError.value = (error as Error).message;
    preview.value = undefined;
  } finally {
    working.value = false;
  }
}

function refreshAfterPublish() {
  publishedRevision.value++;
  void loadPage(page.value);
}

onMounted(search);
</script>

<template>
  <IdiomPublishedLevels
    :revision="publishedRevision"
    @session-expired="emit('sessionExpired')"
  />
  <IdiomBatch
    v-if="canPublish"
    @session-expired="emit('sessionExpired')"
    @published="refreshAfterPublish"
    @reviewed="loadPage(page)"
  />
  <div :class="{ 'workspace idiom-workspace': canPublish }">
    <section class="panel">
      <div class="idiom-list-heading">
        <h1>成语词库</h1>
        <span class="idiom-total" aria-live="polite">
          <template v-if="loading">正在统计…</template>
          <template v-else-if="searchError">总数读取失败</template>
          <template v-else-if="total !== undefined">
            共 <strong>{{ total.toLocaleString("zh-CN") }}</strong> 条
          </template>
          <template v-else>接口未返回总数，请重启 Java 后刷新</template>
        </span>
      </div>
      <form class="filters" @submit.prevent="search">
        <input
          v-model="keyword"
          aria-label="成语关键词"
          placeholder="输入成语或部分汉字"
          maxlength="64"
        />
        <select v-model="difficulty" aria-label="难度">
          <option value="">全部难度</option>
          <option
            v-for="(label, index) in difficultyLabels"
            :key="label"
            :value="String(index + 1)"
          >
            {{ label }}
          </option>
        </select>
        <button :disabled="loading">搜索</button>
      </form>
      <p v-if="searchError" class="error" role="alert">{{ searchError }}</p>
      <p v-else-if="loading" role="status">加载中…</p>
      <p v-else-if="!entries.length">没有找到符合条件的成语。</p>
      <div
        v-if="entries.length && !searchError"
        ref="tableScroll"
        class="idiom-table-scroll"
      >
        <table class="idiom-table">
          <thead>
            <tr>
              <th scope="col">成语 / 拼音</th>
              <th scope="col">难度</th>
              <th scope="col">状态</th>
              <th v-if="canPublish" scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in entries" :key="entry.id">
              <td>
                <strong>{{ entry.term }}</strong
                ><small>{{
                  entry.reviewedPinyin ?? entry.sourcePinyin ?? "待填写"
                }}</small>
              </td>
              <td>
                {{
                  entry.difficulty
                    ? difficultyLabels[entry.difficulty - 1]
                    : "未分级"
                }}
              </td>
              <td>
                {{ entry.reviewStatus === "APPROVED" ? "已审核" : "待审核" }}
              </td>
              <td v-if="canPublish">
                <button
                  type="button"
                  :disabled="
                    working ||
                    questions.length === 3 ||
                    questions.some((q) => q.entryId === entry.id)
                  "
                  @click="select(entry)"
                >
                  选题
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav
        v-if="!searchError && (total !== undefined || entries.length)"
        class="pagination idiom-pagination"
        aria-label="词库分页"
      >
        <select
          v-if="total !== undefined"
          v-model.number="pageSize"
          aria-label="每页条数"
          :disabled="loading"
          @change="loadPage(1)"
        >
          <option :value="10">10 条 / 页</option>
          <option :value="20">20 条 / 页</option>
          <option :value="50">50 条 / 页</option>
        </select>
        <button :disabled="page === 1 || loading" @click="loadPage(page - 1)">
          上一页
        </button>
        <span v-if="totalPages">第 {{ page }} / {{ totalPages }} 页</span>
        <span v-else-if="entries.length">第 {{ page }} 页</span>
        <button :disabled="!hasMore || loading" @click="loadPage(page + 1)">
          下一页
        </button>
      </nav>
    </section>

    <section v-if="canPublish" class="panel editor">
      <h2>新建关卡</h2>
      <p v-if="published" class="success" role="status">{{ published }}</p>
      <fieldset :disabled="working">
        <label
          >关卡名称<input
            v-model="title"
            maxlength="64"
            placeholder="例如：日常成语练习"
        /></label>
        <p v-if="!questions.length">从词库选择 3 道成语题。</p>
        <article
          v-for="(question, index) in questions"
          :key="question.entryId"
          class="question"
        >
          <div class="question-header">
            <h3>{{ question.term }}</h3>
            <div class="row-actions">
              <button
                type="button"
                :disabled="!question.pinyin.trim() || !question.meaning.trim()"
                @click="approveEntry(question)"
              >
                保存审核
              </button>
              <button type="button" @click="questions.splice(index, 1)">
                移除
              </button>
            </div>
          </div>
          <label
            >完整拼音<input
              v-model="question.pinyin"
              maxlength="255"
              placeholder="每个音节用空格分隔"
          /></label>
          <label
            >儿童版释义<textarea
              v-model="question.meaning"
              maxlength="255"
              rows="2"
            />
          </label>
          <div class="question-settings">
            <label
              >挖空字
              <select v-model="question.blankIndex">
                <option
                  v-for="(char, position) in Array.from(question.term)"
                  :key="position"
                  :value="position"
                >
                  第 {{ position + 1 }} 字 · {{ char }}
                </option>
              </select>
            </label>
            <label
              >难度
              <select v-model="question.difficulty">
                <option
                  v-for="(label, index) in difficultyLabels"
                  :key="label"
                  :value="index + 1"
                >
                  {{ label }}
                </option>
              </select>
            </label>
          </div>
        </article>
        <button
          class="primary"
          type="button"
          :disabled="!ready"
          @click="previewLevel"
        >
          预览关卡
        </button>
      </fieldset>
      <p v-if="draftError" class="error" role="alert">{{ draftError }}</p>
      <section v-if="preview" class="preview">
        <h3>
          第 {{ preview.levelNo }} 关 · {{ preview.free ? "免费" : "需解锁" }}
        </h3>
        <ul>
          <li v-for="question in preview.questions" :key="question.entryId">
            {{ question.prompt }} · {{ question.promptPinyin }}（{{
              question.answer
            }}）
          </li>
        </ul>
        <p class="tile-list">
          <span
            v-for="(tile, index) in preview.tiles"
            :key="index"
            :title="tile.pinyin"
            >{{ tile.character }}</span
          >
        </p>
        <button class="primary" :disabled="working" @click="publishLevel">
          发布第 {{ preview.levelNo }} 关
        </button>
      </section>
    </section>
  </div>
</template>
