import assert from 'node:assert/strict';
import { existsSync, mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

// Contract fixtures exercise the real frontend without running sync jobs or a database.
const origin = process.env.SMOKE_URL || 'http://localhost:15780/ersyncui/';
const browserPath = process.env.SMOKE_BROWSER || [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
].find(existsSync);
const browser = await chromium.launch({ headless: true, ...(browserPath ? { executablePath: browserPath } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
const requests = [];
let loginAttempts = 0;
let expired = false;
page.on('pageerror', (error) => errors.push(error.message));
page.on('console', (message) => {
  if (message.type() === 'warning' && /Failed to resolve|Unhandled error|Invalid prop/.test(message.text())) errors.push(message.text());
});
const modules = [
  ['datasource', '数据源管理'], ['datasync', '数据同步'],
  ['datatransfer', '数据传输'], ['combinesync', '合并同步'],
  ['user', '用户管理'], ['role', '角色管理'], ['menu', '菜单管理'],
  ['dept', '部门管理'], ['post', '岗位管理'], ['dict', '字典管理'],
  ['config', '参数设置'], ['notice', '通知公告'],
];
const coreModules = ['datasource', 'datasync', 'datatransfer', 'combinesync'];
const modulePath = (key) => key === 'datasource' ? '/databasesource/datasource' : coreModules.includes(key) ? `/dbsync/${key}` : `/system/${key}`;
const menus = [{
  path: '/databasesource', name: 'Databasesource', component: 'Layout', meta: { title: '数据源管理', icon: 'database' },
  children: [{ path: 'datasource', name: 'Datasource', component: 'system/datasource/index', meta: { title: '数据源管理' } }],
}, {
  path: '/dbsync', name: 'Dbsync', component: 'Layout', meta: { title: '数据同步', icon: 'nested' },
  children: modules.filter(([key]) => coreModules.includes(key) && key !== 'datasource').map(([key, title]) => ({ path: key, name: key, component: `system/${key}/index`, meta: { title } })),
}, {
  path: '/system', name: 'System', component: 'Layout', redirect: 'noRedirect', meta: { title: '系统管理', icon: 'system' },
  children: [...modules.filter(([key]) => !coreModules.includes(key)).map(([key, title]) => ({ path: key, name: key, component: `system/${key}/index`, meta: { title, icon: 'table' } })), {
  path: 'monitor', name: 'Monitor', component: 'ParentView', meta: { title: '系统监控', icon: 'monitor' },
  children: [
    ...['online', 'operlog', 'logininfor', 'job', 'server', 'cache'].map((key) => ({ path: key, name: key, component: `monitor/${key}/index`, meta: { title: key } })),
    { path: 'cache-list', name: 'CacheList', component: 'monitor/cache/list', meta: { title: '缓存列表' } },
  ],
}, {
  path: 'tool', name: 'Tool', component: 'ParentView', meta: { title: '系统工具' },
  children: ['build', 'gen'].map((key) => ({ path: key, name: key, component: `tool/${key}/index`, meta: { title: key } })),
}],
}];
const user = { userId: 1, userName: 'smoke', nickName: '界面验证', avatar: '', email: '', phonenumber: '', sex: '0', dept: { deptName: '测试部门' } };
await page.route('**/ersyncapi/**', async (route) => {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname.replace('/ersyncapi', '');
  requests.push({ path, method: request.method(), data: request.postDataJSON() });
  let result = { code: 200, msg: '操作成功', data: [], rows: [], total: 0 };
  if (path === '/captchaImage') result = { code: 200, captchaEnabled: true, uuid: 'smoke-captcha', img: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jPuoAAAAASUVORK5CYII=' };
  else if (path === '/login') result = ++loginAttempts === 1 ? { code: 500, msg: '验证码错误' } : { code: 200, token: 'smoke-contract-token' };
  else if (expired && path === '/system/datasource/list') result = { code: 401, msg: '登录已过期' };
  else if (path === '/getInfo') result = { code: 200, user, roles: ['admin'], permissions: ['*:*:*'] };
  else if (path === '/getRouters') result.data = menus;
  else if (path === '/system/user/profile') result = { code: 200, data: user, postGroup: '开发', roleGroup: '管理员' };
  else if (path === '/system/dashboard/summary') result.data = { datasourceTotal: 3, taskTotal: 6, todaySyncTotal: 12, todaySyncRows: 12345 };
  else if (path.startsWith('/system/dashboard/')) result.data = { list: [] };
  else if (path.startsWith('/system/dict/data/type/')) result.data = [{ dictLabel: '正常', dictValue: '0', listClass: 'success' }, { dictLabel: '停用', dictValue: '1', listClass: 'danger' }];
  else if (path.startsWith('/system/config/configKey/')) result.data = '';
  else if (path === '/system/user/deptTree' || path === '/system/dept/treeselect') result.data = [{ id: 1, label: '测试部门', children: [] }];
  else if (path === '/system/menu/treeselect') result.data = [];
  else if (path === '/monitor/server') result.data = { cpu: { cpuNum: 8, used: 12, free: 88 }, mem: { total: 16, used: 4, free: 12, usage: 25 }, jvm: { total: 2, used: 1, usage: 50, name: 'OpenJDK', version: '17' }, sys: { computerName: 'smoke', osName: 'Windows' }, sysFiles: [] };
  else if (path === '/monitor/cache') result.data = { info: { redis_version: '7.0', used_memory_human: '12M', used_memory_peak_human: '16M' }, dbSize: 0, commandStats: [{ name: 'get', value: 10 }, { name: 'set', value: 5 }] };
  else if (path === '/system/datasource' && request.method() === 'POST') result.data = null;
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result) });
});

try {
  await page.goto(origin, { waitUntil: 'networkidle', timeout: 120_000 });
  await page.getByPlaceholder('用户名', { exact: true }).fill('smoke');
  await page.getByPlaceholder('密码', { exact: true }).fill('contract-password');
  await page.getByPlaceholder('验证码', { exact: true }).fill('wrong');
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/captchaImage')),
    page.getByRole('button', { name: '登录', exact: true }).click(),
  ]);
  await page.getByRole('button', { name: '登录', exact: true }).waitFor();
  await page.getByPlaceholder('验证码', { exact: true }).fill('1234');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByText('ERSync 工作台', { exact: true }).waitFor({ timeout: 60_000 });
  const login = requests.filter((request) => request.path === '/login').at(-1);
  assert.equal(login.data.username, 'smoke');
  assert.equal(login.data.password, 'contract-password');
  assert.equal(login.data.uuid, 'smoke-captcha');
  assert.equal(login.data.code, '1234');

  for (const [key] of modules) {
    const resource = key === 'dict' ? 'dict/type' : key;
    await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname === `/ersyncapi/system/${resource}/list`),
      page.goto(`${origin}#${modulePath(key)}`, { waitUntil: 'networkidle' }),
    ]);
    await page.locator('.bi-management-page').waitFor();
    await page.locator('.bi-table-toolbar h2').waitFor();
    const layout = await page.evaluate(() => {
      const query = document.querySelector('.bi-search-card');
      const table = document.querySelector('.bi-table-card');
      const fields = [...query.querySelectorAll('.el-form-item:has(.el-form-item__label)')]
        .map((field) => {
          const rect = field.getBoundingClientRect();
          return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom };
        });
      return { gap: table.getBoundingClientRect().top - query.getBoundingClientRect().bottom, fields };
    });
    assert.ok(layout.gap >= 12, `${key} separates query and table cards`);
    for (let i = 0; i < layout.fields.length; i++) {
      for (const other of layout.fields.slice(i + 1)) {
        const field = layout.fields[i];
        assert.ok(
          Math.min(field.right, other.right) <= Math.max(field.left, other.left) ||
          Math.min(field.bottom, other.bottom) <= Math.max(field.top, other.top),
          `${key} query fields must not overlap`,
        );
      }
    }
    if (['datasource', 'datasync', 'datatransfer', 'combinesync'].includes(key)) {
      await page.getByRole('button', { name: '新增', exact: true }).first().click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor();
      assert.ok(await dialog.isVisible(), `${key} create dialog must open`);
      await dialog.getByRole('button', { name: /取\s*消/ }).first().click();
      await dialog.waitFor({ state: 'hidden' });
    }
    console.log(`Checked ${modulePath(key)}`);
  }
  for (const key of ['online', 'operlog', 'logininfor', 'job']) {
    await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname === `/ersyncapi/monitor/${key}/list`),
      page.goto(`${origin}#/system/monitor/${key}`, { waitUntil: 'networkidle' }),
    ]);
    await page.locator('.bi-management-page').waitFor();
    console.log(`Checked /monitor/${key}`);
  }
  for (const [route, api, selector] of [
    ['server', '/monitor/server', '.monitor-grid'],
    ['cache', '/monitor/cache', '.cache-overview'],
    ['cache-list', '/monitor/cache/getNames', '.cache-browser'],
  ]) {
    await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname === `/ersyncapi${api}`),
      page.goto(`${origin}#/system/monitor/${route}`, { waitUntil: 'networkidle' }),
    ]);
    await page.locator(selector).waitFor();
    console.log(`Checked /monitor/${route}`);
  }
  await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname === '/ersyncapi/system/user/profile'),
    page.goto(`${origin}#/user/profile`, { waitUntil: 'networkidle' }),
  ]);
  await page.getByText('基本资料', { exact: true }).first().waitFor();
  await page.goto(`${origin}#/system/tool/build`, { waitUntil: 'networkidle' });
  await page.getByText('表单构建', { exact: true }).first().waitFor();
  await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname === '/ersyncapi/tool/gen/list'),
    page.goto(`${origin}#/system/tool/gen`, { waitUntil: 'networkidle' }),
  ]);
  await page.goto(`${origin}#/databasesource/datasource`, { waitUntil: 'networkidle' });
  await page.locator('.bi-table-content .el-table').getByText('数据源名称', { exact: true }).waitFor();
  mkdirSync('test-results', { recursive: true });
  await page.screenshot({ path: 'test-results/datasource.png', fullPage: true, animations: 'disabled' });
  await page.goto(`${origin}#/index`, { waitUntil: 'networkidle' });
  await page.getByText('ERSync 工作台', { exact: true }).waitFor();
  await page.screenshot({ path: 'test-results/dashboard.png', fullPage: true, animations: 'disabled' });
  expired = true;
  await page.goto(`${origin}#/databasesource/datasource`, { waitUntil: 'networkidle' });
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByPlaceholder('用户名', { exact: true }).waitFor();
  assert.deepEqual(errors, [], `Browser runtime errors: ${errors.join('\n')}`);
  console.log(JSON.stringify({ passed: true, pages: 23, coreCreateDialogs: 4, apiRequests: requests.length }));
} catch (error) {
  mkdirSync('test-results', { recursive: true });
  await page.screenshot({ path: 'test-results/failure.png', fullPage: true });
  console.error('PAGE:', await page.locator('body').innerText());
  console.error('RUNTIME ERRORS:', errors);
  throw error;
} finally {
  await browser.close();
}
