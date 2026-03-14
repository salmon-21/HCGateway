import Toast from 'react-native-toast-message';
import {
  initHealthConnect,
  readHealthRecords,
  readSingleRecord,
  isDetailedRecordType,
  RECORD_TYPES,
  insertHealthRecords,
  deleteHealthRecords,
} from './healthConnect';
import { apiSyncRecords, apiDeleteRecords } from './api';
import { setPlain } from './storage';
import { updateSyncProgress } from './foregroundService';
import { postPushErrorNotification } from './notifications';

export const sync = async (appState, customStartTime, customEndTime) => {
  await initHealthConnect();
  console.log('Syncing data...');

  let numRecords = 0;
  let numRecordsSynced = 0;

  Toast.show({
    type: 'info',
    text1: customStartTime ? 'Syncing from custom time...' : 'Syncing data...',
  });

  const currentTime = new Date().toISOString();

  let startTime;
  if (customStartTime) {
    startTime = customStartTime;
  } else if (appState.fullSyncMode) {
    startTime = new Date(Date.now() - 29 * 24 * 60 * 60 * 1000).toISOString();
  } else {
    startTime = appState.lastSync
      || new Date(Date.now() - 29 * 24 * 60 * 60 * 1000).toISOString();
  }

  if (!customStartTime) {
    await setPlain('lastSync', currentTime);
    appState.lastSync = currentTime;
  }

  const endTime = customEndTime || new Date().toISOString();

  for (const recordType of RECORD_TYPES) {
    let records;
    try {
      console.log(`Reading records for ${recordType} from ${startTime} to ${endTime}`);
      records = await readHealthRecords(recordType, startTime, endTime);
    } catch (err) {
      console.log(err);
      continue;
    }

    console.log(recordType);
    numRecords += records.length;

    if (isDetailedRecordType(recordType)) {
      for (let j = 0; j < records.length; j++) {
        await new Promise((resolve) => setTimeout(resolve, 3000));
        try {
          const record = await readSingleRecord(recordType, records[j].metadata.id);
          await apiSyncRecords(appState.apiBase, appState.login, recordType, record);
        } catch (err) {
          console.log(err);
        }

        numRecordsSynced += 1;
        try {
          updateSyncProgress(numRecordsSynced, numRecords);
        } catch {}
      }
    } else {
      await apiSyncRecords(appState.apiBase, appState.login, recordType, records);
      numRecordsSynced += records.length;
      try {
        updateSyncProgress(numRecordsSynced, numRecords);
      } catch {}
    }
  }
};

export const handlePush = async (message) => {
  await initHealthConnect();
  const data = JSON.parse(message.data);
  console.log(data);

  try {
    const ids = await insertHealthRecords(data);
    console.log('Records inserted successfully: ', { ids });
  } catch (error) {
    postPushErrorNotification(data[0].recordType, error.message);
  }
};

export const handleDel = async (appState, message) => {
  await initHealthConnect();
  const data = JSON.parse(message.data);
  console.log(data);

  deleteHealthRecords(data.recordType, data.uuids);
  apiDeleteRecords(appState.apiBase, appState.login, data.recordType, data.uuids);
};
