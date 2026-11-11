<script setup lang="ts">
import { ref, watch } from "vue";
import { adminRequest, AdminRequestError } from "./adminApi";

interface PublishedPage {
  total: number;
  levels: { levelNo: number; title: string; free: boolean }[];
  hasMore: boolean;
}

const props = defineProps<{ revision: number }>();
const emit = defineEmits<{ sessionExpired: [] }>();
const data = ref<PublishedPage>();
const page = ref(1);
const loading = ref(false);
const error = ref("");
let latestRequest = 0;

async function load(number: number) {
  const requestId = ++latestRequest;
  loading.value = true;
  error.value = "";
  try {
    const result = await adminRequest<PublishedPage>(
      "/levels/published?page=" + number,
    );
    if (requestId !== latestRequest) return;
    data.value = result;
    page.value = number;
  } catch (cause) {
    if (requestId !== latestRequest) return;
    if (cause instanceof AdminRequestError && cause.status === 401) {
      emit("sessionExpired");
    } else {
      error.value = (cause as Error).message;
    }
  } finally {
    if (requestId === latestRequest) loading.value = false;
  }
}

watch(
  () => props.revision,
  () => void load(1),
  { immediate: true },
);
</script>

<template>
  <details class="panel batch-panel">
    <summary>
      已发布关卡
      <span v-if="data && !error" class="idiom-total" aria-live="polite">
        共 <strong>{{ data.total.toLocaleString("zh-CN") }}</strong> 关
      </span>
      <span v-else-if="loading" role="status">正在统计…</span>
      <span v-else-if="error" class="error">总数读取失败</span>
    </summary>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button type="button" @click="load(page)">重试</button>
    </p>
    <template v-else-if="data">
      <table v-if="data.levels.length">
        <thead>
          <tr>
            <th scope="col">关卡</th>
            <th scope="col">名称</th>
            <th scope="col">解锁状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="level in data.levels" :key="level.levelNo">
            <td>第 {{ level.levelNo }} 关</td>
            <td>{{ level.title }}</td>
            <td>{{ level.free ? "免费" : "需解锁" }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else>暂无已发布关卡。</p>
      <nav
        v-if="page > 1 || data.hasMore"
        class="pagination"
        aria-label="已发布关卡分页"
      >
        <button
          type="button"
          :disabled="loading || page === 1"
          @click="load(page - 1)"
        >
          上一页
        </button>
        <span>第 {{ page }} / {{ Math.ceil(data.total / 10) }} 页</span>
        <button
          type="button"
          :disabled="loading || !data.hasMore"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </nav>
    </template>
  </details>
</template>
