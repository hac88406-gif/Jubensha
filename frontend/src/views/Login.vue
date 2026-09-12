<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '../api/user'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const loading = ref(false)
const activeTab = ref('login') // login | register

// ---------- 表单 ----------
const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({
  username: '',
  password: '',
  nickname: '',
  role: 'ROLE_PLAYER', // 默认玩家，可注册店长
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const registerRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, min: 5, message: '密码至少 5 位', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
}

const loginRef = ref(null)
const registerRef = ref(null)

/** 登录成功后按角色回家 */
function goHome() {
  // 若登录页带了 redirect 参数且是当前角色可达路由，则跳回原目标
  const redirect = route.query.redirect
  // 玩家端：ROLE_PLAYER 等非管理端角色；管理端：仅 ROLE_SHOP_OWNER 店长
  router.replace(redirect || (user.isAdminSide ? '/admin/sessions' : '/player'))
}

/** 登录 */
async function doLogin() {
  // 校验未通过：由表单内联展示错误，直接返回（不抛未捕获 reject，避免 Vue warn）
  try {
    await loginRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const data = await userApi.login(loginForm)
    user.setLogin(data)
    ElMessage.success(`欢迎回来，${data.nickname || data.username || ''}`)
    goHome()
  } catch (e) {
    // 请求失败的错误提示已由 http.js 响应拦截器统一 ElMessage 弹出，这里兜底打印便于排查
    console.error('[login] 登录失败:', e)
  } finally {
    loading.value = false
  }
}

/** 注册（成功即切换到登录页并回填用户名） */
async function doRegister() {
  // 校验未通过：由表单内联展示错误，直接返回
  try {
    await registerRef.value.validate()
  } catch {
    return
  }
  if (registerForm.password.length < 5) {
    ElMessage.warning('密码至少 5 位')
    return
  }
  loading.value = true
  try {
    await userApi.register(registerForm)
    ElMessage.success('注册成功，请登录')
    loginForm.username = registerForm.username
    activeTab.value = 'login'
  } catch (e) {
    // 错误提示已由 http.js 拦截器弹出，兜底打印
    console.error('[register] 注册失败:', e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card u-card">
      <!-- 品牌区 -->
      <div class="brand">
        <div class="brand-logo">🕵️</div>
        <div class="brand-title">剧本杀预约平台</div>
        <div class="brand-sub">选本 · 订场 · 一键开玩</div>
      </div>

      <el-tabs v-model="activeTab" stretch>
        <!-- ========== 登录 ========== -->
        <el-tab-pane label="登录" name="login">
          <el-form
            ref="loginRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            size="large"
            @keyup.enter="doLogin"
          >
            <el-form-item label="用户名" prop="username">
              <el-input v-model="loginForm.username" placeholder="admin / 玩家用户名" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" show-password />
            </el-form-item>
            <el-button type="primary" size="large" class="submit" :loading="loading" @click="doLogin">
              登 录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ========== 注册 ========== -->
        <el-tab-pane label="注册" name="register">
          <el-form
            ref="registerRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            size="large"
          >
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" placeholder="登录用户名" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="至少 5 位" show-password />
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="registerForm.nickname" placeholder="展示昵称" />
            </el-form-item>
            <el-form-item label="角色">
              <el-radio-group v-model="registerForm.role">
                <el-radio-button value="ROLE_PLAYER">玩家</el-radio-button>
                <el-radio-button value="ROLE_SHOP_OWNER">店长</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-button type="primary" size="large" class="submit" :loading="loading" @click="doRegister">
              注 册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 80% -10%, rgba(6, 182, 212, 0.12), transparent 60%),
    radial-gradient(1000px 500px at 10% 110%, rgba(13, 148, 136, 0.14), transparent 60%),
    var(--color-bg-base);
}

.login-card {
  width: 420px;
  max-width: 100%;
  padding: 36px 36px 28px;
  box-sizing: border-box;
}

.brand {
  text-align: center;
  margin-bottom: 20px;
}

.brand-logo {
  font-size: 44px;
}

.brand-title {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text-main);
}

.brand-sub {
  margin-top: 6px;
  font-size: 13px;
  color: var(--color-text-sub);
}

.submit {
  width: 100%;
  margin-top: 4px;
}
</style>