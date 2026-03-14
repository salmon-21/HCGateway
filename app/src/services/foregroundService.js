import ReactNativeForegroundService from '@supersami/rn-foreground-service';

const NOTIFICATION_ID = 1244;

export const registerService = () => {
  ReactNativeForegroundService.register();
};

export const startService = () => {
  return ReactNativeForegroundService.start({
    id: NOTIFICATION_ID,
    title: 'HCGateway Sync Service',
    message: 'HCGateway is working in the background to sync your data.',
    icon: 'ic_launcher',
    setOnlyAlertOnce: true,
    color: '#000000',
  });
};

export const addSyncTask = (syncFn, delay) => {
  ReactNativeForegroundService.add_task(syncFn, {
    delay,
    onLoop: true,
    taskId: 'hcgateway_sync',
    onError: (e) => console.log('Error logging:', e),
  });
};

export const addRefreshTask = (refreshFn) => {
  ReactNativeForegroundService.add_task(refreshFn, {
    delay: 10800 * 1000,
    onLoop: true,
    taskId: 'refresh_token',
    onError: (e) => console.log('Error logging:', e),
  });
};

export const updateSyncTask = (syncFn, delay) => {
  ReactNativeForegroundService.update_task(syncFn, { delay });
};

export const updateSyncProgress = (current, total) => {
  const isComplete = current === total;
  ReactNativeForegroundService.update({
    id: NOTIFICATION_ID,
    title: 'HCGateway Sync Progress',
    message: isComplete
      ? 'HCGateway is working in the background to sync your data.'
      : `HCGateway is currently syncing... [${current}/${total}]`,
    icon: 'ic_launcher',
    setOnlyAlertOnce: true,
    color: '#000000',
    ...(isComplete ? {} : { progress: { max: total, curr: current } }),
  });
};
