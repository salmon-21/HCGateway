import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import Toast from 'react-native-toast-message';
import { requestNotifications } from 'react-native-permissions';
import * as Sentry from '@sentry/react-native';
import { get, setPlain, delkey } from '../services/storage';
import { apiLogin, apiRefreshToken, setCachedToken } from '../services/api';
import { askForPermissions } from '../services/healthConnect';
import { requestFCMPermission, setupFCMHandlers } from '../services/notifications';
import { addSyncTask, addRefreshTask, startService, updateSyncTask } from '../services/foregroundService';
import { sync, handlePush, handleDel } from '../services/sync';

const AppStateContext = createContext(null);

export const useAppState = () => useContext(AppStateContext);

const DEFAULT_API_BASE = 'https://api.hcgateway.shuchir.dev';
const DEFAULT_TASK_DELAY = 7200 * 1000;

export function AppStateProvider({ children }) {
  const [login, setLogin] = useState(null);
  const [apiBase, setApiBase] = useState(DEFAULT_API_BASE);
  const [lastSync, setLastSync] = useState(null);
  const [taskDelay, setTaskDelay] = useState(DEFAULT_TASK_DELAY);
  const [fullSyncMode, setFullSyncMode] = useState(true);
  const [sentryEnabled, setSentryEnabled] = useState(true);

  const appStateRef = useRef({});

  useEffect(() => {
    appStateRef.current = { login, apiBase, lastSync, fullSyncMode };
  }, [login, apiBase, lastSync, fullSyncMode]);

  useEffect(() => {
    const loadState = async () => {
      const savedApiBase = await get('apiBase');
      if (savedApiBase) {
        setApiBase(savedApiBase);
        Toast.show({ type: 'success', text1: 'API Base URL loaded' });
      } else {
        Toast.show({ type: 'error', text1: 'API Base URL not found. Using default server.' });
      }

      const savedLogin = await get('login');
      if (savedLogin) {
        setLogin(savedLogin);
        setCachedToken(savedLogin);
      }

      const savedLastSync = await get('lastSync');
      if (savedLastSync) setLastSync(savedLastSync);

      const savedFullSyncMode = await get('fullSyncMode');
      if (savedFullSyncMode !== null && savedFullSyncMode !== undefined) {
        setFullSyncMode(savedFullSyncMode === 'true');
      }

      const savedTaskDelay = await get('taskDelay');
      if (savedTaskDelay) setTaskDelay(Number(savedTaskDelay));
    };

    loadState();
  }, []);

  useEffect(() => {
    if (!login) return;

    requestNotifications(['alert']).then(({ status, settings }) => {
      console.log(status, settings);
    });

    addSyncTask(() => sync(appStateRef.current), taskDelay);
    addRefreshTask(() => refreshToken());
    startService().then(() => console.log('Foreground service started'));
  }, [login]);

  useEffect(() => {
    setupFCMHandlers(
      (data) => handlePush(data),
      (data) => handleDel(appStateRef.current, data),
    );
  }, []);

  const doLogin = useCallback(async (form) => {
    Toast.show({ type: 'info', text1: 'Logging in...', autoHide: false });

    try {
      const fcmToken = await requestFCMPermission();
      const response = await apiLogin(apiBase, { ...form, fcmToken });

      if ('token' in response.data) {
        console.log(response.data);
        await setPlain('login', response.data.token);
        setLogin(response.data.token);
        setCachedToken(response.data.token);
        await setPlain('refreshToken', response.data.refresh);
        Toast.hide();
        Toast.show({ type: 'success', text1: 'Logged in successfully' });
        askForPermissions();
      } else {
        Toast.hide();
        Toast.show({ type: 'error', text1: 'Login failed', text2: response.data.error });
      }
    } catch (err) {
      Toast.hide();
      Toast.show({ type: 'error', text1: 'Login failed', text2: err.message });
    }
  }, [apiBase]);

  const doLogout = useCallback(async () => {
    await delkey('login');
    setLogin(null);
    Toast.show({ type: 'success', text1: 'Logged out successfully' });
  }, []);

  const refreshToken = useCallback(async () => {
    const refreshTokenValue = await get('refreshToken');
    if (!refreshTokenValue) return;

    try {
      const response = await apiRefreshToken(apiBase, refreshTokenValue);
      if ('token' in response.data) {
        console.log(response.data);
        await setPlain('login', response.data.token);
        setLogin(response.data.token);
        setCachedToken(response.data.token);
        await setPlain('refreshToken', response.data.refresh);
        Toast.show({ type: 'success', text1: 'Token refreshed successfully' });
      } else {
        Toast.show({ type: 'error', text1: 'Token refresh failed', text2: response.data.error });
        setLogin(null);
        delkey('login');
      }
    } catch (err) {
      Toast.show({ type: 'error', text1: 'Token refresh failed', text2: err.message });
      setLogin(null);
      delkey('login');
    }
  }, [apiBase]);

  const doSync = useCallback((customStartTime, customEndTime) => {
    return sync(appStateRef.current, customStartTime, customEndTime);
  }, []);

  const updateApiBase = useCallback(async (value) => {
    setApiBase(value);
    await setPlain('apiBase', value);
  }, []);

  const updateTaskDelay = useCallback(async (hours) => {
    const delay = hours * 60 * 60 * 1000;
    setTaskDelay(delay);
    await setPlain('taskDelay', String(delay));
    updateSyncTask(() => sync(appStateRef.current), delay);
    Toast.show({
      type: 'success',
      text1: `Sync interval updated to ${hours} ${hours === 1 ? 'hour' : 'hours'}`,
    });
  }, []);

  const updateFullSyncMode = useCallback(async (value) => {
    setFullSyncMode(value);
    await setPlain('fullSyncMode', value.toString());
  }, []);

  const updateSentryEnabled = useCallback(async (value) => {
    if (value) {
      Sentry.init({
        dsn: 'https://e4a201b96ea602d28e90b5e4bbe67aa6@sentry.shuchir.dev/6',
      });
      Toast.show({ type: 'success', text1: 'Sentry enabled' });
    } else {
      Sentry.close();
      Toast.show({ type: 'success', text1: 'Sentry disabled' });
    }
    setSentryEnabled(value);
    await setPlain('sentryEnabled', value.toString());
  }, []);

  const value = {
    login,
    apiBase,
    lastSync,
    taskDelay,
    fullSyncMode,
    sentryEnabled,
    doLogin,
    doLogout,
    doSync,
    updateApiBase,
    updateTaskDelay,
    updateFullSyncMode,
    updateSentryEnabled,
  };

  return (
    <AppStateContext.Provider value={value}>
      {children}
    </AppStateContext.Provider>
  );
}
