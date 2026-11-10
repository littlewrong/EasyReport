import { requestClient } from '#/api/request';
import { ElLoading, ElMessage } from 'element-plus';
import { tansParams } from './ruoyi';
export default function request({ url, params, data, ...config }) {
  if (params) config.paramsSerializer = value => tansParams(value).replace(/&$/, '');
  if (data instanceof FormData) config.headers = { ...config.headers, 'Content-Type': undefined };
  return requestClient.request(url, { ...config, params, data });
}
export async function download(url, params, filename, config = {}) {
  const loading = ElLoading.service({ text: '正在下载数据，请稍候' });
  try {
    const data = await request({ url, method: 'post', data: tansParams(params), responseType: 'blob', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, ...config });
    if (data.type?.includes('json')) throw new Error(JSON.parse(await data.text()).msg || '下载失败');
    const href = URL.createObjectURL(data), link = document.createElement('a');
    link.href = href; link.download = filename; link.click();
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  } catch (error) { ElMessage.error(error.message || '下载失败'); } finally { loading.close(); }
}
