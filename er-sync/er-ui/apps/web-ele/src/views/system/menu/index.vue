<template>
  <BiTablePage title="菜单列表" :show-search="showSearch">
    <template #search>
      <el-form
        :model="queryParams"
        ref="queryForm"
        :inline="true"
        v-show="showSearch"
      >
        <el-form-item label="菜单名称" prop="menuName">
          <el-input
            v-model="queryParams.menuName"
            placeholder="请输入菜单名称"
            clearable
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select
            v-model="queryParams.status"
            placeholder="菜单状态"
            clearable
          >
            <el-option
              v-for="dict in dict.type.sys_normal_disable"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item class="bi-search-actions">
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery"
            >搜索</el-button
          >
        </el-form-item>
      </el-form>
    </template>

    <template #toolbar>
      <el-button
        type="primary"
        icon="el-icon-plus"
        @click="handleAdd"
        v-hasPermi="['system:menu:add']"
        >新增菜单</el-button
      >

      <el-button icon="el-icon-sort" @click="toggleExpandAll"
        >展开/折叠</el-button
      >

      <right-toolbar
        v-model:showSearch="showSearch"
        @queryTable="getList"
      ></right-toolbar>
    </template>

    <el-table
      height="100%"
      v-if="refreshTable"
      v-loading="loading"
      :data="menuList"
      row-key="menuId"
      :default-expand-all="isExpandAll"
      :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
    >
      <el-table-column
        header-align="center"
        prop="menuName"
        label="菜单名称"
        :show-overflow-tooltip="true"
        width="160"
      ></el-table-column>
      <el-table-column
        header-align="center"
        prop="icon"
        label="图标"
        align="center"
        width="100"
      >
        <template #default="scope">
          <svg-icon :icon-class="scope.row.icon" />
        </template>
      </el-table-column>
      <el-table-column
        align="center"
        header-align="center"
        prop="orderNum"
        label="排序"
        width="60"
      ></el-table-column>
      <el-table-column
        align="center"
        header-align="center"
        prop="perms"
        label="权限标识"
        :show-overflow-tooltip="true"
      ></el-table-column>
      <el-table-column
        align="center"
        header-align="center"
        prop="component"
        label="组件路径"
        :show-overflow-tooltip="true"
      ></el-table-column>
      <el-table-column
        align="center"
        header-align="center"
        prop="status"
        label="状态"
        width="80"
      >
        <template #default="scope">
          <dict-tag
            :options="dict.type.sys_normal_disable"
            :value="scope.row.status"
          />
        </template>
      </el-table-column>
      <el-table-column
        header-align="center"
        label="创建时间"
        align="center"
        prop="createTime"
      >
        <template #default="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column
        header-align="center"
        label="操作"
        align="center"
        fixed="right"
        width="220"
      >
        <template #default="scope">
          <el-button
            size="small"
            link
            type="primary"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['system:menu:edit']"
            >编辑</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            @click="handleAdd(scope.row)"
            v-hasPermi="['system:menu:add']"
            >新增</el-button
          >
          <el-button
            size="small"
            link
            type="primary"
            @click="handleDelete(scope.row)"
            v-hasPermi="['system:menu:remove']"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <!-- 添加或修改菜单对话框 -->
    <el-drawer
      class="bi-management-drawer"
      :title="title"
      v-model="open"
      size="720px"
      append-to-body
    >
      <el-form
        class="bi-editor-form"
        label-position="top"
        ref="form"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="上级菜单" prop="parentId">
              <treeselect
                v-model="form.parentId"
                :options="menuOptions"
                :normalizer="normalizer"
                :show-count="true"
                placeholder="选择上级菜单"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="菜单类型" prop="menuType">
              <el-radio-group v-model="form.menuType">
                <el-radio value="M">目录</el-radio>
                <el-radio value="C">菜单</el-radio>
                <el-radio value="F">按钮</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24" v-if="form.menuType != 'F'">
            <el-form-item label="菜单图标" prop="icon">
              <el-popover
                placement="bottom-start"
                width="460"
                trigger="click"
                @show="$refs['iconSelect'].reset()"
              >
                <IconSelect
                  ref="iconSelect"
                  @selected="selected"
                  :active-icon="form.icon"
                />
                <template #reference
                  ><el-input
                    v-model="form.icon"
                    placeholder="点击选择图标"
                    readonly
                  >
                    <template #prefix
                      ><svg-icon
                        v-if="form.icon"
                        :icon-class="form.icon"
                        style="width: 25px" />
                      <i v-else class="el-icon-search el-input__icon"
                    /></template> </el-input
                ></template>
              </el-popover>
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24">
            <el-form-item label="显示排序" prop="orderNum">
              <el-input-number
                v-model="form.orderNum"
                controls-position="right"
                :min="0"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24">
            <el-form-item label="菜单名称" prop="menuName">
              <el-input v-model="form.menuName" placeholder="请输入菜单名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24" v-if="form.menuType == 'C'">
            <el-form-item prop="routeName">
              <el-input v-model="form.routeName" placeholder="请输入路由名称" />
              <template #label
                ><span>
                  <el-tooltip
                    content="默认不填则和路由地址相同：如地址为：`user`，则名称为`User`（注意：为避免名字的冲突，特殊情况下请自定义，保证唯一性）"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  路由名称
                </span></template
              >
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24" v-if="form.menuType != 'F'">
            <el-form-item prop="isFrame">
              <template #label
                ><span>
                  <el-tooltip
                    content="选择是外链则路由地址需要以`http(s)://`开头"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  是否外链
                </span></template
              >
              <el-radio-group v-model="form.isFrame">
                <el-radio value="0">是</el-radio>
                <el-radio value="1">否</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24" v-if="form.menuType != 'F'">
            <el-form-item prop="path">
              <template #label
                ><span>
                  <el-tooltip
                    content="访问的路由地址，如：`user`，如外网地址需内链访问则以`http(s)://`开头"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  路由地址
                </span></template
              >
              <el-input v-model="form.path" placeholder="请输入路由地址" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24" v-if="form.menuType == 'C'">
            <el-form-item prop="component">
              <template #label
                ><span>
                  <el-tooltip
                    content="访问的组件路径，如：`system/user/index`，默认在`views`目录下"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  组件路径
                </span></template
              >
              <el-input v-model="form.component" placeholder="请输入组件路径" />
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24" v-if="form.menuType != 'M'">
            <el-form-item prop="perms">
              <el-input
                v-model="form.perms"
                placeholder="请输入权限标识"
                maxlength="100"
              />
              <template #label
                ><span>
                  <el-tooltip
                    content="控制器中定义的权限字符，如：@PreAuthorize(`@ss.hasPermi('system:user:list')`)"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  权限字符
                </span></template
              >
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24" v-if="form.menuType == 'C'">
            <el-form-item prop="query">
              <el-input
                v-model="form.query"
                placeholder="请输入路由参数"
                maxlength="255"
              />
              <template #label
                ><span>
                  <el-tooltip
                    content='访问路由的默认传递参数，如：`{"id": 1, "name": "ry"}`'
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  路由参数
                </span></template
              >
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24" v-if="form.menuType == 'C'">
            <el-form-item prop="isCache">
              <template #label
                ><span>
                  <el-tooltip
                    content="选择是则会被`keep-alive`缓存，需要匹配组件的`name`和地址保持一致"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  是否缓存
                </span></template
              >
              <el-radio-group v-model="form.isCache">
                <el-radio value="0">缓存</el-radio>
                <el-radio value="1">不缓存</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="20">
          <el-col :span="12" :xs="24" v-if="form.menuType != 'F'">
            <el-form-item prop="visible">
              <template #label
                ><span>
                  <el-tooltip
                    content="选择隐藏则路由将不会出现在侧边栏，但仍然可以访问"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  显示状态
                </span></template
              >
              <el-radio-group v-model="form.visible">
                <el-radio
                  v-for="dict in dict.type.sys_show_hide"
                  :key="dict.value"
                  :value="dict.value"
                  >{{ dict.label }}</el-radio
                >
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12" :xs="24">
            <el-form-item prop="status">
              <template #label
                ><span>
                  <el-tooltip
                    content="选择停用则路由将不会出现在侧边栏，也不能被访问"
                    placement="top"
                  >
                    <el-icon><component is="el-icon-question" /></el-icon>
                  </el-tooltip>
                  菜单状态
                </span></template
              >
              <el-radio-group v-model="form.status">
                <el-radio
                  v-for="dict in dict.type.sys_normal_disable"
                  :key="dict.value"
                  :value="dict.value"
                  >{{ dict.label }}</el-radio
                >
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer
        ><div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div></template
      >
    </el-drawer>
  </BiTablePage>
</template>

<script>
import {
  listMenu,
  getMenu,
  delMenu,
  addMenu,
  updateMenu,
} from '#/legacy/api/system/menu';
import Treeselect from '#/legacy/components/TreeSelect/index.vue';
import IconSelect from '#/legacy/components/IconSelect/index.vue';

export default {
  name: 'Menu',
  dicts: ['sys_show_hide', 'sys_normal_disable'],
  components: { Treeselect, IconSelect },
  data() {
    return {
      // 遮罩层
      loading: true,
      // 显示搜索条件
      showSearch: true,
      // 菜单表格树数据
      menuList: [],
      // 菜单树选项
      menuOptions: [],
      // 弹出层标题
      title: '',
      // 是否显示弹出层
      open: false,
      // 是否展开，默认全部折叠
      isExpandAll: false,
      // 重新渲染表格状态
      refreshTable: true,
      // 查询参数
      queryParams: {
        menuName: undefined,
        visible: undefined,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        menuName: [
          { required: true, message: '菜单名称不能为空', trigger: 'blur' },
        ],
        orderNum: [
          { required: true, message: '菜单顺序不能为空', trigger: 'blur' },
        ],
        path: [
          { required: true, message: '路由地址不能为空', trigger: 'blur' },
        ],
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    // 选择图标
    selected(name) {
      this.form.icon = name;
    },
    /** 查询菜单列表 */
    getList() {
      this.loading = true;
      listMenu(this.queryParams).then((response) => {
        this.menuList = this.handleTree(response.data, 'menuId');
        this.loading = false;
      });
    },
    /** 转换菜单数据结构 */
    normalizer(node) {
      if (node.children && !node.children.length) {
        delete node.children;
      }
      return {
        id: node.menuId,
        label: node.menuName,
        children: node.children,
      };
    },
    /** 查询菜单下拉树结构 */
    getTreeselect() {
      listMenu().then((response) => {
        this.menuOptions = [];
        const menu = { menuId: 0, menuName: '主类目', children: [] };
        menu.children = this.handleTree(response.data, 'menuId');
        this.menuOptions.push(menu);
      });
    },
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    // 表单重置
    reset() {
      this.form = {
        menuId: undefined,
        parentId: 0,
        menuName: undefined,
        icon: undefined,
        menuType: 'M',
        orderNum: undefined,
        isFrame: '1',
        isCache: '0',
        visible: '0',
        status: '0',
      };
      this.$refs.form?.clearValidate();
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm('queryForm');
      this.handleQuery();
    },
    /** 新增按钮操作 */
    handleAdd(row) {
      this.reset();
      this.getTreeselect();
      if (row != null && row.menuId) {
        this.form.parentId = row.menuId;
      } else {
        this.form.parentId = 0;
      }
      this.open = true;
      this.title = '添加菜单';
    },
    /** 展开/折叠操作 */
    toggleExpandAll() {
      this.refreshTable = false;
      this.isExpandAll = !this.isExpandAll;
      this.$nextTick(() => {
        this.refreshTable = true;
      });
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      this.getTreeselect();
      getMenu(row.menuId).then((response) => {
        this.form = response.data;
        this.open = true;
        this.title = '修改菜单';
      });
    },
    /** 提交按钮 */
    submitForm: function () {
      this.$refs['form'].validate((valid) => {
        if (valid) {
          if (this.form.menuId != undefined) {
            updateMenu(this.form).then((response) => {
              this.$modal.msgSuccess('修改成功');
              this.open = false;
              this.getList();
            });
          } else {
            addMenu(this.form).then((response) => {
              this.$modal.msgSuccess('新增成功');
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      this.$modal
        .confirm('是否确认删除名称为"' + row.menuName + '"的数据项？')
        .then(function () {
          return delMenu(row.menuId);
        })
        .then(() => {
          this.getList();
          this.$modal.msgSuccess('删除成功');
        })
        .catch(() => {});
    },
  },
};
</script>
