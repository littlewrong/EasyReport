<template>
  <el-form
    ref="form"
    :model="form"
    :rules="rules"
    label-position="top"
    class="profile-form"
  >
    <el-form-item label="用户昵称" prop="nickName">
      <el-input v-model="form.nickName" maxlength="30" />
    </el-form-item>
    <el-form-item label="手机号码" prop="phonenumber">
      <el-input v-model="form.phonenumber" maxlength="11" />
    </el-form-item>
    <el-form-item label="邮箱" prop="email">
      <el-input v-model="form.email" maxlength="50" />
    </el-form-item>
    <el-form-item label="性别">
      <el-radio-group v-model="form.sex">
        <el-radio value="0">男</el-radio>
        <el-radio value="1">女</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="submit">保存</el-button>
      <el-button @click="close">关闭</el-button>
    </el-form-item>
  </el-form>
</template>

<script>
import { updateUserProfile } from '#/legacy/api/system/user';

export default {
  emits: ['saved'],
  props: {
    user: {
      type: Object,
    },
  },
  data() {
    return {
      form: {},
      // 表单校验
      rules: {
        nickName: [
          { required: true, message: '用户昵称不能为空', trigger: 'blur' },
        ],
        email: [
          { required: true, message: '邮箱地址不能为空', trigger: 'blur' },
          {
            type: 'email',
            message: '请输入正确的邮箱地址',
            trigger: ['blur', 'change'],
          },
        ],
        phonenumber: [
          { required: true, message: '手机号码不能为空', trigger: 'blur' },
          {
            pattern: /^1[3|4|5|6|7|8|9][0-9]\d{8}$/,
            message: '请输入正确的手机号码',
            trigger: 'blur',
          },
        ],
      },
    };
  },
  watch: {
    user: {
      handler(user) {
        if (user) {
          this.form = {
            nickName: user.nickName,
            phonenumber: user.phonenumber,
            email: user.email,
            sex: user.sex,
          };
        }
      },
      immediate: true,
    },
  },
  methods: {
    submit() {
      this.$refs['form'].validate((valid) => {
        if (valid) {
          updateUserProfile(this.form).then((response) => {
            this.$modal.msgSuccess('修改成功');
            this.user.phonenumber = this.form.phonenumber;
            this.user.email = this.form.email;
            this.user.nickName = this.form.nickName;
            this.$emit('saved');
          });
        }
      });
    },
    close() {
      this.$tab.closePage();
    },
  },
};
</script>

<style scoped>
.profile-form {
  max-width: 640px;
}
.profile-form :deep(.el-form-item) {
  margin-bottom: 24px;
}
.profile-form :deep(.el-form-item__label) {
  padding-bottom: 8px;
  font-weight: 500;
}
.profile-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
  padding-top: 8px;
}
</style>
