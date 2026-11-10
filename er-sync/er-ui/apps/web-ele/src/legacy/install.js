import { reactive } from 'vue';
import * as Icons from '@vben/icons';
import ElementPlus, { ElLoading, ElMessage, ElMessageBox, ElNotification } from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { useAccessStore, useTabbarStore, useUserStore } from '@vben/stores';
import { router } from '#/router';
import { getDicts } from './api/system/dict/data';
import { getConfigKey } from './api/system/config';
import { addDateRange, handleTree, parseTime, resetForm, selectDictLabel, selectDictLabels } from './utils/ruoyi';
import { download } from './utils/request';
import { checkPermi, checkRole } from './utils/permission';
import DictTag from './components/DictTag/index.vue';
import Pagination from './components/Pagination/index.vue';
import RightToolbar from './components/RightToolbar/index.vue';
import SvgIcon from './components/SvgIcon/index.vue';
import Editor from './components/Editor/index.vue';
import FileUpload from './components/FileUpload/index.vue';
import ImageUpload from './components/ImageUpload/index.vue';
import ImagePreview from './components/ImagePreview/index.vue';
import BiTablePage from '#/components/bi-table-page.vue';
import './styles.css';
import './management.css';

const dictionaryCache = new Map();
const dictionaryState = reactive({ type: {} });
async function loadDictionary(type) {
  if (!dictionaryCache.has(type)) dictionaryCache.set(type, getDicts(type).then(response => {
    dictionaryState.type[type] = (response.data || []).map(raw => ({ label: raw.dictLabel, value: raw.dictValue, raw }));
    return dictionaryState.type[type];
  }).catch(error => { dictionaryCache.delete(type); throw error; }));
  return dictionaryCache.get(type);
}
function clearDictionary(type) {
  if (type) { dictionaryCache.delete(type); delete dictionaryState.type[type]; }
  else { dictionaryCache.clear(); Object.keys(dictionaryState.type).forEach(key => delete dictionaryState.type[key]); }
}
let overlay;
const modal = {
  msg: ElMessage.info, msgSuccess: ElMessage.success, msgError: ElMessage.error, msgWarning: ElMessage.warning,
  notify: ElNotification.info, notifySuccess: ElNotification.success, notifyError: ElNotification.error, notifyWarning: ElNotification.warning,
  alert: content => ElMessageBox.alert(content, '系统提示'),
  alertError: content => ElMessageBox.alert(content, '系统提示', { type: 'error' }),
  alertSuccess: content => ElMessageBox.alert(content, '系统提示', { type: 'success' }),
  alertWarning: content => ElMessageBox.alert(content, '系统提示', { type: 'warning' }),
  confirm: content => ElMessageBox.confirm(content, '系统提示', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }),
  prompt: content => ElMessageBox.prompt(content, '系统提示', { confirmButtonText: '确定', cancelButtonText: '取消' }),
  loading: text => { overlay?.close(); overlay = ElLoading.service({ text, lock: true }); },
  closeLoading: () => { overlay?.close(); overlay = undefined; },
};
export function installLegacy(app) {
  app.component('BiTablePage', BiTablePage);
  app.use(ElementPlus, { locale: zhCn });
  const icons = { search: 'Search', refresh: 'RotateCw', 'refresh-right': 'RotateCw', plus: 'Plus', edit: 'UserRoundPen', delete: 'X', download: 'Download', upload: 'ArrowUp', upload2: 'ArrowUp', document: 'BookOpenText', 'document-copy': 'Copy', view: 'Eye', key: 'LockKeyhole', user: 'UserRoundPen', menu: 'Menu', sort: 'ArrowRightLeft', 'arrow-up': 'ArrowUp', 'arrow-down': 'ArrowDown', 'caret-right': 'ChevronRight', 'd-arrow-right': 'ChevronsRight', 'circle-check': 'CircleCheckBig', 'circle-close': 'CircleX', close: 'X', question: 'CircleHelp', 'picture-outline': 'ImagePlus', time: 'RotateCw', unlock: 'LockKeyhole', 's-operation': 'Settings', cpu: 'SquareCode', tickets: 'List', monitor: 'InspectionPanel', 'coffee-cup': 'SquareCode', receiving: 'Inbox', collection: 'BookOpenText', odometer: 'RotateCw', 'pie-chart': 'Circle' };
  for (const [name, icon] of Object.entries(icons)) app.component(`el-icon-${name}`, Icons[icon]);
  for (const [name, component] of Object.entries({ DictTag, Pagination, RightToolbar, SvgIcon, Editor, FileUpload, ImageUpload, ImagePreview })) app.component(name, component);
  const globals = app.config.globalProperties;
  Object.assign(globals, { getDicts, getConfigKey, parseTime, resetForm, addDateRange, selectDictLabel, selectDictLabels, download, handleTree, $modal: modal });
  globals.$auth = { hasPermi: code => checkPermi([code]), hasPermiOr: checkPermi, hasPermiAnd: codes => codes.every(code => checkPermi([code])), hasRole: role => checkRole([role]), hasRoleOr: checkRole, hasRoleAnd: roles => roles.every(role => checkRole([role])) };
  const current = () => router.currentRoute.value;
  globals.$tab = {
    refreshPage: () => useTabbarStore().refresh(router),
    closeOpenPage: async route => { const tab = current(); if (route) await router.push(route); await useTabbarStore().closeTab(tab, router); },
    closePage: route => useTabbarStore().closeTab(route || current(), router),
    closeAllPage: () => useTabbarStore().closeAllTabs(router),
    closeLeftPage: route => useTabbarStore().closeLeftTabs(route || current()),
    closeRightPage: route => useTabbarStore().closeRightTabs(route || current()),
    closeOtherPage: route => useTabbarStore().closeOtherTabs(route || current()),
    openPage: (title, path, query) => router.push({ path, query }),
    updatePage: route => useTabbarStore().setTabTitle(route, route.meta?.title),
  };
  globals.$store = {
    get getters() { return { get device() { return window.innerWidth < 768 ? 'mobile' : 'desktop'; }, get permissions() { return useAccessStore().accessCodes; }, get roles() { return useUserStore().userInfo?.roles || []; } }; },
    dispatch(action, value) {
      if (action === 'dict/cleanDict') clearDictionary();
      if (action === 'dict/removeDict') clearDictionary(value);
      return Promise.resolve();
    },
  };
  app.mixin({
    beforeCreate() { for (const type of this.$options.dicts || []) dictionaryState.type[type] ||= []; },
    data() { return this.$options.dicts ? { dict: dictionaryState } : {}; },
    created() { for (const type of this.$options.dicts || []) loadDictionary(type).catch(() => {}); },
  });
  for (const [name, check] of [['hasPermi', checkPermi], ['hasRole', checkRole]]) {
    const apply = (el, binding) => { el.style.display = check(binding.value) ? el.dataset.accessDisplay || '' : 'none'; };
    app.directive(name, { mounted(el, binding) { el.dataset.accessDisplay = el.style.display; apply(el, binding); }, updated: apply });
  }
  app.directive('clipboard', {
    mounted(el, binding) {
      if (binding.arg === 'copy') el.__copy = binding.value;
      if (binding.arg === 'success') el.__copySuccess = binding.value;
      if (!el.__copyHandler) { el.__copyHandler = async () => { try { await navigator.clipboard.writeText(String(el.__copy ?? '')); el.__copySuccess?.(); } catch { ElMessage.error('复制失败，请手动复制'); } }; el.addEventListener('click', el.__copyHandler); }
    },
    updated(el, binding) { if (binding.arg === 'copy') el.__copy = binding.value; if (binding.arg === 'success') el.__copySuccess = binding.value; },
    unmounted(el) { el.removeEventListener('click', el.__copyHandler); },
  });
}
