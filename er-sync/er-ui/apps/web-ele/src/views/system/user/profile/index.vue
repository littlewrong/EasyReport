<template>
  <Page title="个人中心" description="管理账号资料与登录安全">
    <div class="profile-grid">
      <el-card class="profile-summary" shadow="never">
        <div class="profile-identity">
          <userAvatar />
          <h2>{{ user.nickName || user.userName || '我的账号' }}</h2>
          <span>{{ user.userName }}</span>
        </div>
        <el-descriptions :column="1" class="profile-facts">
          <el-descriptions-item label="手机号码">{{
            user.phonenumber || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="用户邮箱">{{
            user.email || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="所属部门">{{
            user.dept?.deptName || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="所属岗位">{{
            postGroup || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="所属角色">{{
            roleGroup || '—'
          }}</el-descriptions-item>
          <el-descriptions-item label="创建日期">{{
            user.createTime || '—'
          }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
      <el-card class="profile-settings" shadow="never">
        <el-tabs v-model="selectedTab">
          <el-tab-pane label="基本资料" name="userinfo">
            <h3>基本资料</h3>
            <p class="profile-description">
              更新个人信息，方便团队识别和联系。
            </p>
            <userInfo :user="user" @saved="getUser" />
          </el-tab-pane>
          <el-tab-pane label="修改密码" name="resetPwd">
            <h3>修改密码</h3>
            <p class="profile-description">
              请使用 6 至 20 位字符设置新的登录密码。
            </p>
            <resetPwd />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </Page>
</template>

<script>
import { Page } from '@vben/common-ui';
import userAvatar from './userAvatar.vue';
import userInfo from './userInfo.vue';
import resetPwd from './resetPwd.vue';
import { getUserProfile } from '#/legacy/api/system/user';

export default {
  name: 'Profile',
  components: { Page, userAvatar, userInfo, resetPwd },
  data() {
    return {
      user: {},
      roleGroup: '',
      postGroup: '',
      selectedTab: 'userinfo',
    };
  },
  created() {
    const activeTab =
      this.$route.query.activeTab || this.$route.params.activeTab;
    if (activeTab) {
      this.selectedTab = activeTab;
    }
    this.getUser();
  },
  methods: {
    getUser() {
      getUserProfile().then((response) => {
        this.user = response.data;
        this.roleGroup = response.roleGroup;
        this.postGroup = response.postGroup;
      });
    },
  },
};
</script>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.profile-summary,
.profile-settings {
  border: 0;
  border-radius: 8px;
  background: hsl(var(--card));
}
.profile-identity {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 12px 0 24px;
}
.profile-identity h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.profile-identity > span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.profile-facts :deep(.el-descriptions__body) {
  background: transparent;
}
.profile-facts :deep(.el-descriptions__label) {
  display: inline-block;
  min-width: 76px;
  color: var(--el-text-color-secondary);
}
.profile-facts :deep(.el-descriptions__cell) {
  padding-bottom: 18px;
  overflow-wrap: anywhere;
}
.profile-settings :deep(.el-card__body) {
  padding: 24px;
}
.profile-settings :deep(.el-tabs__header) {
  margin-bottom: 24px;
}
.profile-settings h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.profile-description {
  margin: 8px 0 24px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
@media (max-width: 1000px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
