import messaging from '@react-native-firebase/messaging';
import { Notifications } from 'react-native-notifications';
import * as Sentry from '@sentry/react-native';
import { get } from './storage';

export const setupNotificationChannel = () => {
  Notifications.setNotificationChannel({
    channelId: 'push-errors',
    name: 'Push Errors',
    importance: 5,
    description: 'Alerts for push errors',
    groupId: 'push-errors',
    groupName: 'Errors',
    enableLights: true,
    enableVibration: true,
    showBadge: true,
    vibrationPattern: [200, 1000, 500, 1000, 500],
  });
};

export const initSentry = () => {
  Sentry.init({
    dsn: 'https://e4a201b96ea602d28e90b5e4bbe67aa6@sentry.shuchir.dev/6',
  });

  get('sentryEnabled').then((res) => {
    if (res === 'false') {
      Sentry.close();
    }
  });
};

export const requestFCMPermission = async () => {
  try {
    await messaging().requestPermission();
    const token = await messaging().getToken();
    console.log('Device Token:', token);
    return token;
  } catch (error) {
    console.log('Permission or Token retrieval error:', error);
  }
};

export const setupFCMHandlers = (handlePush, handleDel) => {
  messaging().setBackgroundMessageHandler(async (remoteMessage) => {
    if (remoteMessage.data.op === 'PUSH') handlePush(remoteMessage.data);
    if (remoteMessage.data.op === 'DEL') handleDel(remoteMessage.data);
  });

  messaging().onMessage((remoteMessage) => {
    if (remoteMessage.data.op === 'PUSH') handlePush(remoteMessage.data);
    if (remoteMessage.data.op === 'DEL') handleDel(remoteMessage.data);
  });
};

export const postPushErrorNotification = (recordType, errorMessage) => {
  Notifications.postLocalNotification({
    body: 'Error: ' + errorMessage,
    title: `Push failed for ${recordType}`,
    silent: false,
    category: 'Push Errors',
    fireDate: new Date(),
    android_channel_id: 'push-errors',
  });
};
