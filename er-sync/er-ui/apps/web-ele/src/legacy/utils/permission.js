import { useAccessStore, useUserStore } from '@vben/stores';
export function checkPermi(values) {
  const codes = useAccessStore().accessCodes || [];
  return Array.isArray(values) && values.length > 0 && (codes.includes('*:*:*') || values.some(value => codes.includes(value)));
}
export function checkRole(values) {
  const roles = useUserStore().userInfo?.roles || [];
  return Array.isArray(values) && values.length > 0 && (roles.includes('admin') || values.some(value => roles.includes(value)));
}
