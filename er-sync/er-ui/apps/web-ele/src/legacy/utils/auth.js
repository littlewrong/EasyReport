import { useAccessStore } from '@vben/stores';
export function getToken() { return useAccessStore().accessToken; }
export function setToken(token) { useAccessStore().setAccessToken(token); }
export function removeToken() { useAccessStore().setAccessToken(null); }
