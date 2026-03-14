import axios from 'axios';
import { get, setPlain } from './storage';

let cachedToken = null;

export const setCachedToken = (token) => {
  cachedToken = token;
};

const refreshAndRetry = async (apiBase, requestFn) => {
  const refreshTokenValue = await get('refreshToken');
  if (!refreshTokenValue) throw new Error('No refresh token available');

  const response = await axios.post(`${apiBase}/api/v2/refresh`, {
    refresh: refreshTokenValue,
  });

  if ('token' in response.data) {
    cachedToken = response.data.token;
    await setPlain('login', response.data.token);
    await setPlain('refreshToken', response.data.refresh);
    return requestFn(response.data.token);
  }

  throw new Error('Token refresh failed');
};

const withAutoRefresh = async (apiBase, token, requestFn) => {
  const activeToken = cachedToken || token;
  try {
    return await requestFn(activeToken);
  } catch (err) {
    if (err.response && err.response.status === 403) {
      console.log('Got 403, attempting token refresh...');
      return refreshAndRetry(apiBase, requestFn);
    }
    throw err;
  }
};

export const apiLogin = (apiBase, form) =>
  axios.post(`${apiBase}/api/v2/login`, form);

export const apiRefreshToken = (apiBase, refreshToken) =>
  axios.post(`${apiBase}/api/v2/refresh`, { refresh: refreshToken });

export const apiSyncRecords = (apiBase, token, recordType, data) =>
  withAutoRefresh(apiBase, token, (t) =>
    axios.post(`${apiBase}/api/v2/sync/${recordType}`, { data }, {
      headers: { Authorization: `Bearer ${t}` },
    })
  );

export const apiDeleteRecords = (apiBase, token, recordType, uuids) =>
  withAutoRefresh(apiBase, token, (t) =>
    axios.delete(`${apiBase}/api/v2/sync/${recordType}`, {
      data: { uuid: uuids },
      headers: { Authorization: `Bearer ${t}` },
    })
  );
