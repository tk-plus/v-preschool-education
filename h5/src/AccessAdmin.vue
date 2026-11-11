<script setup lang="ts">
import { onMounted, ref } from "vue";
import { adminRequest, AdminRequestError } from "./adminApi";

const props = defineProps<{ operatorId: number }>();
const emit = defineEmits<{ sessionExpired: [] }>();

interface User {
  id: number;
  username: string;
  roleId: number;
  enabled: boolean;
}
interface Role {
  id: number;
  name: string;
  systemRole: boolean;
  permissions: string[];
}
interface Overview {
  users: User[];
  roles: Role[];
  permissions: { code: string; label: string }[];
}

const overview = ref<Overview>({ users: [], roles: [], permissions: [] });
const newUser = ref({ username: "", password: "", roleId: 0 });
const roleDraft = ref<{ id?: number; name: string; permissions: string[] }>({
  name: "",
  permissions: [],
});
const reset = ref<{ id: number; username: string; password: string }>();
const busy = ref(false);
const error = ref("");
const message = ref("");

async function load() {
  overview.value = await adminRequest<Overview>("/access");
}

async function perform(action: () => Promise<void>, success = "") {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await action();
    await load();
    message.value = success;
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

function createUser() {
  void perform(async () => {
    await adminRequest("/access/users", newUser.value);
    newUser.value = { username: "", password: "", roleId: 0 };
  }, "账号已创建");
}

function saveUser(user: User) {
  void perform(async () => {
    await adminRequest("/access/users/" + user.id, {
      roleId: user.roleId,
      enabled: user.enabled,
    });
  }, "账号已更新");
}

function resetPassword() {
  const draft = reset.value;
  if (!draft) return;
  void perform(async () => {
    await adminRequest("/access/users/" + draft.id + "/password", {
      password: draft.password,
    });
    reset.value = undefined;
  }, "密码已重置");
}

function editRole(role?: Role) {
  roleDraft.value = role
    ? { id: role.id, name: role.name, permissions: [...role.permissions] }
    : { name: "", permissions: [] };
}

function saveRole() {
  const { id, name, permissions } = roleDraft.value;
  void perform(async () => {
    await adminRequest(id ? "/access/roles/" + id : "/access/roles", {
      name,
      permissions,
    });
    editRole();
  }, "角色已保存");
}

function deleteRole(role: Role) {
  if (!window.confirm(`删除角色「${role.name}」？`)) return;
  void perform(async () => {
    await adminRequest("/access/roles/" + role.id + "/delete", {});
    if (roleDraft.value.id === role.id) editRole();
  }, "角色已删除");
}

onMounted(() => void perform(async () => {}));
</script>

<template>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="message" class="success" role="status">{{ message }}</p>
  <div class="workspace">
    <section class="panel">
      <h2>管理员账号</h2>
      <table>
        <thead>
          <tr>
            <th>账号</th>
            <th>角色</th>
            <th>启用</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in overview.users" :key="user.id">
            <td>{{ user.username }}</td>
            <td>
              <select
                v-model="user.roleId"
                :aria-label="user.username + ' 的角色'"
                :disabled="busy || user.id === props.operatorId"
              >
                <option
                  v-for="role in overview.roles"
                  :key="role.id"
                  :value="role.id"
                >
                  {{ role.name }}
                </option>
              </select>
            </td>
            <td>
              <input
                v-model="user.enabled"
                type="checkbox"
                :aria-label="'启用 ' + user.username"
                :disabled="busy || user.id === props.operatorId"
              />
            </td>
            <td class="row-actions">
              <button
                :disabled="busy || user.id === props.operatorId"
                @click="saveUser(user)"
              >
                保存
              </button>
              <button
                :disabled="busy"
                @click="
                  reset = { id: user.id, username: user.username, password: '' }
                "
              >
                重置密码
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <form v-if="reset" class="access-form" @submit.prevent="resetPassword">
        <label
          >{{ reset.username }} 的新密码<input
            v-model="reset.password"
            type="password"
            autocomplete="new-password"
            minlength="8"
            maxlength="256"
            required
        /></label>
        <div class="row-actions">
          <button class="primary" :disabled="busy">保存新密码</button>
          <button type="button" :disabled="busy" @click="reset = undefined">
            取消
          </button>
        </div>
      </form>
      <form class="access-form" @submit.prevent="createUser">
        <h3>创建账号</h3>
        <label
          >账号<input
            v-model="newUser.username"
            autocomplete="off"
            maxlength="64"
            required
        /></label>
        <label
          >初始密码<input
            v-model="newUser.password"
            type="password"
            autocomplete="new-password"
            minlength="8"
            maxlength="256"
            required
        /></label>
        <label
          >角色<select v-model="newUser.roleId">
            <option :value="0" disabled>选择角色</option>
            <option
              v-for="role in overview.roles"
              :key="role.id"
              :value="role.id"
            >
              {{ role.name }}
            </option>
          </select></label
        >
        <button class="primary" :disabled="busy || !newUser.roleId">
          创建账号
        </button>
      </form>
    </section>
    <section class="panel">
      <h2>角色与权限</h2>
      <table>
        <thead>
          <tr>
            <th>角色</th>
            <th>权限</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="role in overview.roles" :key="role.id">
            <td>{{ role.name }}</td>
            <td>
              {{
                role.systemRole
                  ? "全部管理权限"
                  : overview.permissions
                      .filter((permission) =>
                        role.permissions.includes(permission.code),
                      )
                      .map((permission) => permission.label)
                      .join("、") || "未分配"
              }}
            </td>
            <td class="row-actions" v-if="!role.systemRole">
              <button :disabled="busy" @click="editRole(role)">编辑</button>
              <button :disabled="busy" @click="deleteRole(role)">删除</button>
            </td>
            <td v-else>系统角色</td>
          </tr>
        </tbody>
      </table>
      <form class="access-form" @submit.prevent="saveRole">
        <h3>{{ roleDraft.id ? "编辑角色" : "创建角色" }}</h3>
        <label
          >角色名称<input v-model="roleDraft.name" maxlength="64" required
        /></label>
        <label
          v-for="permission in overview.permissions"
          :key="permission.code"
          class="checkbox-label"
        >
          <input
            v-model="roleDraft.permissions"
            type="checkbox"
            :value="permission.code"
          />
          {{ permission.label }}
        </label>
        <div class="row-actions">
          <button class="primary" :disabled="busy">保存角色</button>
          <button
            v-if="roleDraft.id"
            type="button"
            :disabled="busy"
            @click="editRole()"
          >
            取消编辑
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
