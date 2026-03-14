import {
  initialize,
  requestPermission,
  readRecords,
  readRecord,
  insertRecords,
  deleteRecordsByUuids,
} from 'react-native-health-connect';
import Toast from 'react-native-toast-message';

export const RECORD_TYPES = [
  'ActiveCaloriesBurned', 'BasalBodyTemperature', 'BloodGlucose', 'BloodPressure',
  'BasalMetabolicRate', 'BodyFat', 'BodyTemperature', 'BoneMass',
  'CyclingPedalingCadence', 'CervicalMucus', 'ExerciseSession', 'Distance',
  'ElevationGained', 'FloorsClimbed', 'HeartRate', 'Height',
  'Hydration', 'LeanBodyMass', 'MenstruationFlow', 'MenstruationPeriod',
  'Nutrition', 'OvulationTest', 'OxygenSaturation', 'Power',
  'RespiratoryRate', 'RestingHeartRate', 'SleepSession', 'Speed',
  'Steps', 'StepsCadence', 'TotalCaloriesBurned', 'Vo2Max',
  'Weight', 'WheelchairPushes',
];

const DETAILED_RECORD_TYPES = ['SleepSession', 'Speed', 'HeartRate'];

const PERMISSION_LIST = RECORD_TYPES.flatMap(recordType => [
  { accessType: 'read', recordType },
  { accessType: 'write', recordType },
]);

export const initHealthConnect = () => initialize();

export const askForPermissions = async () => {
  await initialize();

  const grantedPermissions = await requestPermission(PERMISSION_LIST);
  console.log(grantedPermissions);

  if (grantedPermissions.length < PERMISSION_LIST.length) {
    Toast.show({
      type: 'error',
      text1: 'Permissions not granted',
      text2: 'Please visit settings to grant all permissions.',
    });
  }
};

export const readHealthRecords = async (recordType, startTime, endTime) => {
  const records = await readRecords(recordType, {
    timeRangeFilter: {
      operator: 'between',
      startTime,
      endTime,
    },
  });
  return records.records;
};

export const readSingleRecord = (recordType, id) => readRecord(recordType, id);

export const insertHealthRecords = (data) => insertRecords(data);

export const deleteHealthRecords = (recordType, uuids) =>
  deleteRecordsByUuids(recordType, uuids, uuids);

export const isDetailedRecordType = (recordType) =>
  DETAILED_RECORD_TYPES.includes(recordType);
