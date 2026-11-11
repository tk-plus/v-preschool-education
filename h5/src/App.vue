<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import IdiomAdmin from "./IdiomAdmin.vue";
import AccessAdmin from "./AccessAdmin.vue";
import { adminRequest, AdminRequestError } from "./adminApi";

interface AdminSession {
  id: number;
  username: string;
  roleName: string;
  permissions: string[];
}

const navigation = [
  {
    id: "learning",
    label: "学习模块",
    items: [{ id: "idiom", label: "成语填字", permission: "IDIOM_READ" }],
  },
  {
    id: "system",
    label: "系统管理",
    items: [{ id: "access", label: "用户与角色", permission: "ACCESS_MANAGE" }],
  },
] as const;

type Section = (typeof navigation)[number]["items"][number]["id"];

const account = ref<AdminSession | null>(null);
const username = ref("");
const password = ref("");
const checking = ref(true);
const busy = ref(false);
const error = ref("");
const section = ref<Section>();
const visibleNavigation = computed(() =>
  navigation
    .map((group) => ({
      ...group,
      items: group.items.filter((item) =>
        account.value?.permissions.includes(item.permission),
      ),
    }))
    .filter((group) => group.items.length > 0),
);

function openSession(session: AdminSession) {
  account.value = session;
  section.value = visibleNavigation.value[0]?.items[0]?.id;
}

onMounted(async () => {
  try {
    openSession(await adminRequest<AdminSession>("/session"));
  } catch (cause) {
    if (!(cause instanceof AdminRequestError) || cause.status !== 401) {
      error.value = (cause as Error).message;
    }
  } finally {
    checking.value = false;
  }
});

async function login() {
  busy.value = true;
  error.value = "";
  try {
    openSession(
      await adminRequest<AdminSession>("/login", {
        username: username.value.trim(),
        password: password.value,
      }),
    );
    password.value = "";
  } catch (cause) {
    error.value = (cause as Error).message;
  } finally {
    busy.value = false;
  }
}

async function logout() {
  busy.value = true;
  error.value = "";
  try {
    await adminRequest<void>("/logout", {});
    account.value = null;
  } catch (cause) {
    if (cause instanceof AdminRequestError && cause.status === 401) {
      account.value = null;
    } else {
      error.value = (cause as Error).message;
    }
  } finally {
    busy.value = false;
  }
}

function sessionExpired() {
  account.value = null;
  error.value = "登录已过期，请重新登录。";
}
</script>

<template>
  <main v-if="checking" class="login-shell">
    <p role="status">正在检查登录状态…</p>
  </main>
  <div v-else-if="account" class="admin-layout">
    <aside class="sidebar">
      <span class="brand">知芽星球</span>
      <nav
        v-if="visibleNavigation.length"
        class="admin-menu"
        aria-label="管理端导航"
      >
        <div
          v-for="group in visibleNavigation"
          :key="group.id"
          class="admin-menu-group"
        >
          <h2>{{ group.label }}</h2>
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            :aria-pressed="section === item.id"
            @click="section = item.id"
          >
            {{ item.label }}
          </button>
        </div>
      </nav>
      <div class="account-actions">
        <div>
          <strong>{{ account.username }}</strong>
          <small>{{ account.roleName }}</small>
        </div>
        <button :disabled="busy" type="button" @click="logout">退出登录</button>
      </div>
    </aside>
    <main class="shell">
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <IdiomAdmin
        v-if="section === 'idiom' && account.permissions.includes('IDIOM_READ')"
        :can-publish="account.permissions.includes('LEVEL_PUBLISH')"
        @session-expired="sessionExpired"
      />
      <AccessAdmin
        v-else-if="
          section === 'access' && account.permissions.includes('ACCESS_MANAGE')
        "
        :operator-id="account.id"
        @session-expired="sessionExpired"
      />
      <p v-else>当前角色还没有可用的管理权限。</p>
    </main>
  </div>
  <main v-else class="login-shell">
    <section class="panel login-panel">
      <p class="brand">知芽星球</p>
      <h1>管理端登录</h1>
      <form class="login-form" @submit.prevent="login">
        <label>
          账号
          <input
            v-model="username"
            name="username"
            autocomplete="username"
            maxlength="64"
            required
          />
        </label>
        <label>
          密码
          <input
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            maxlength="256"
            required
          />
        </label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button
          class="primary"
          :disabled="busy || !username.trim() || !password"
          type="submit"
        >
          {{ busy ? "正在登录…" : "登录" }}
        </button>
      </form>
    </section>
  </main>
</template>
