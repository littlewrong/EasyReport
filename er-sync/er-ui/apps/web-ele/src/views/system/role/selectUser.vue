<template>
  <!-- 授权用户 -->
  <el-drawer
    class="bi-management-drawer bi-user-select-drawer"
    title="选择授权用户"
    v-model="visible"
    size="880px"
    append-to-body
  >
    <el-form
      class="bi-user-select-search"
      :model="queryParams"
      ref="queryForm"
      label-position="top"
    >
      <el-form-item label="用户名称" prop="userName">
        <el-input
          v-model="queryParams.userName"
          placeholder="请输入用户名称"
          clearable
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="手机号码" prop="phonenumber">
        <el-input
          v-model="queryParams.phonenumber"
          placeholder="请输入手机号码"
          clearable
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item class="bi-user-select-actions">
        <el-button type="primary" icon="el-icon-search" @click="handleQuery"
          >搜索</el-button
        >
        <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>
    <div class="bi-user-select-table">
      <el-table
        @row-click="clickRow"
        ref="table"
        :data="userList"
        @selection-change="handleSelectionChange"
        height="100%"
      >
        <el-table-column
          align="center"
          type="selection"
          width="55"
        ></el-table-column>
        <el-table-column
          label="用户名称"
          prop="userName"
          :show-overflow-tooltip="true"
        />
        <el-table-column
          label="用户昵称"
          prop="nickName"
          :show-overflow-tooltip="true"
        />
        <el-table-column
          label="邮箱"
          prop="email"
          :show-overflow-tooltip="true"
        />
        <el-table-column
          label="手机"
          prop="phonenumber"
          :show-overflow-tooltip="true"
        />
        <el-table-column label="状态" align="center" prop="status">
          <template #default="scope">
            <dict-tag
              :options="dict.type.sys_normal_disable"
              :value="scope.row.status"
            />
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          align="center"
          prop="createTime"
          width="180"
        >
          <template #default="scope">
            <span>{{ parseTime(scope.row.createTime) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="total > 0"
        :total="total"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        @pagination="getList"
      />
    </div>
    <template #footer
      ><div class="dialog-footer">
        <el-button type="primary" @click="handleSelectUser">确 定</el-button>
        <el-button @click="visible = false">取 消</el-button>
      </div></template
    >
  </el-drawer>
</template>

<script>
import {
  unallocatedUserList,
  authUserSelectAll,
} from '#/legacy/api/system/role';
export default {
  dicts: ['sys_normal_disable'],
  props: {
    // 角色编号
    roleId: {
      type: [Number, String],
    },
  },
  data() {
    return {
      // 遮罩层
      visible: false,
      // 选中数组值
      userIds: [],
      // 总条数
      total: 0,
      // 未授权用户数据
      userList: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        roleId: undefined,
        userName: undefined,
        phonenumber: undefined,
      },
    };
  },
  methods: {
    // 显示弹框
    show() {
      this.queryParams.roleId = this.roleId;
      this.getList();
      this.visible = true;
    },
    clickRow(row) {
      this.$refs.table.toggleRowSelection(row);
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.userIds = selection.map((item) => item.userId);
    },
    // 查询表数据
    getList() {
      unallocatedUserList(this.queryParams).then((res) => {
        this.userList = res.rows;
        this.total = res.total;
      });
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm('queryForm');
      this.handleQuery();
    },
    /** 选择授权用户操作 */
    handleSelectUser() {
      const roleId = this.queryParams.roleId;
      const userIds = this.userIds.join(',');
      if (userIds == '') {
        this.$modal.msgError('请选择要分配的用户');
        return;
      }
      authUserSelectAll({ roleId: roleId, userIds: userIds }).then((res) => {
        this.$modal.msgSuccess(res.msg);
        this.visible = false;
        this.$emit('ok');
      });
    },
  },
};
</script>

<style scoped>
.bi-user-select-search {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
  padding-bottom: 24px;
}
.bi-user-select-search :deep(.el-form-item) {
  margin: 0;
}
.bi-user-select-actions {
  grid-column: 1/-1;
  justify-self: end;
}
.bi-user-select-table {
  height: calc(100dvh - 360px);
  min-height: 240px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.bi-user-select-table > .el-table {
  flex: 1;
  min-height: 0;
}
.bi-user-select-table > .pagination-container {
  flex: none;
  overflow: auto;
  max-width: 100%;
}
@media (max-width: 600px) {
  .bi-user-select-search {
    grid-template-columns: 1fr;
  }
  .bi-user-select-table {
    height: 400px;
  }
}
</style>
