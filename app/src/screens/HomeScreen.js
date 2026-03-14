import React, { useState } from 'react';
import { View, ScrollView, StyleSheet } from 'react-native';
import { Text, TextInput, Button, Card, Divider, useTheme } from 'react-native-paper';
import M3Switch from '../components/M3Switch';
import { SafeAreaView } from 'react-native-safe-area-context';
import Toast from 'react-native-toast-message';
import { useAppState } from '../hooks/useAppState';
import SyncWarning from '../components/SyncWarning';
import DateRangePickerModal from '../components/DateRangePicker';

const formatDateToISOString = (date) => {
  if (!date) return null;
  const d = new Date(date);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}T00:00:00.000Z`;
};

const formatDateToReadable = (date) => {
  if (!date) return 'Not selected';
  return new Date(date).toLocaleDateString();
};

const formatLastSync = (lastSync) => {
  if (!lastSync) return 'Never';
  const d = new Date(lastSync);
  return d.toLocaleString();
};

export default function HomeScreen() {
  const {
    lastSync, apiBase, taskDelay, fullSyncMode, sentryEnabled,
    doLogout, doSync, updateApiBase, updateTaskDelay,
    updateFullSyncMode, updateSentryEnabled,
  } = useAppState();
  const theme = useTheme();

  const [showSyncWarning, setShowSyncWarning] = useState(false);
  const [customStartDate, setCustomStartDate] = useState(new Date());
  const [customEndDate, setCustomEndDate] = useState(new Date());
  const [useCustomDates, setUseCustomDates] = useState(false);
  const [showDatePickerModal, setShowDatePickerModal] = useState(false);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.colors.background }]}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <Text variant="headlineMedium" style={styles.title}>HCGateway</Text>

        {/* Status Card */}
        <Card style={styles.card} mode="contained">
          <Card.Content>
            <View style={styles.statusRow}>
              <View style={styles.statusDot} />
              <Text variant="titleMedium">Connected</Text>
            </View>
            <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant, marginTop: 4 }}>
              Last Sync: {formatLastSync(lastSync)}
            </Text>
          </Card.Content>
        </Card>

        {/* Sync Card */}
        <Card style={styles.card} mode="contained">
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>Sync</Text>

            <View style={styles.dateRangeRow}>
              <View style={styles.flex}>
                <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>Range</Text>
                <Text variant="bodyMedium">
                  {formatDateToReadable(customStartDate)} - {formatDateToReadable(customEndDate)}
                </Text>
              </View>
              <Button mode="outlined" onPress={() => setShowDatePickerModal(true)} compact>
                Select Dates
              </Button>
            </View>

            <Button
              mode="contained"
              onPress={() => {
                if (!useCustomDates) {
                  doSync();
                } else if (customStartDate && customEndDate) {
                  doSync(formatDateToISOString(customStartDate), formatDateToISOString(customEndDate));
                }
              }}
              style={styles.syncButton}
              contentStyle={styles.buttonContent}
              icon="sync"
            >
              {useCustomDates ? 'Sync Selected Range' : 'Sync Now'}
            </Button>
          </Card.Content>
        </Card>

        {/* Settings Card */}
        <Card style={styles.card} mode="contained">
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>Settings</Text>

            <TextInput
              label="API Base URL"
              mode="outlined"
              defaultValue={apiBase}
              onChangeText={updateApiBase}
              style={styles.input}
            />

            <TextInput
              label="Sync Interval (hours)"
              mode="outlined"
              keyboardType="numeric"
              defaultValue={(taskDelay / (1000 * 60 * 60)).toString()}
              onChangeText={(text) => {
                const hours = Number(text);
                if (hours > 0) updateTaskDelay(hours);
              }}
              style={styles.input}
            />

            <Divider style={styles.divider} />

            <View style={styles.switchRow}>
              <View style={styles.flex}>
                <Text variant="bodyLarge">Full 30-day sync</Text>
                <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
                  Sync all data from the past 30 days
                </Text>
              </View>
              <M3Switch
                value={fullSyncMode}
                onValueChange={(value) => {
                  if (!value) {
                    setShowSyncWarning(true);
                  } else {
                    updateFullSyncMode(value);
                    Toast.show({
                      type: 'info',
                      text1: 'Sync mode updated',
                      text2: 'Will sync full 30 days of data',
                    });
                  }
                }}
              />
            </View>

            {showSyncWarning && (
              <SyncWarning onDismiss={() => setShowSyncWarning(false)} />
            )}

            <Divider style={styles.divider} />

            <View style={styles.switchRow}>
              <View style={styles.flex}>
                <Text variant="bodyLarge">Sentry</Text>
                <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant }}>
                  Error reporting
                </Text>
              </View>
              <M3Switch value={sentryEnabled} onValueChange={updateSentryEnabled} />
            </View>
          </Card.Content>
        </Card>

        {/* Logout */}
        <Button
          mode="outlined"
          onPress={doLogout}
          style={styles.logoutButton}
          textColor={theme.colors.error}
          icon="logout"
        >
          Logout
        </Button>
      </ScrollView>

      <DateRangePickerModal
        visible={showDatePickerModal}
        onClose={() => setShowDatePickerModal(false)}
        startDate={customStartDate}
        endDate={customEndDate}
        onStartDateChange={(date) => {
          setUseCustomDates(true);
          setCustomStartDate(date);
        }}
        onEndDateChange={(date) => {
          setUseCustomDates(true);
          setCustomEndDate(date);
        }}
        onApply={() => {
          setUseCustomDates(true);
          setShowDatePickerModal(false);
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingTop: 16,
    paddingBottom: 32,
  },
  title: {
    fontWeight: 'bold',
    marginBottom: 16,
  },
  card: {
    borderRadius: 16,
    marginBottom: 12,
  },
  sectionTitle: {
    fontWeight: '600',
    marginBottom: 12,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: '#34a853',
  },
  dateRangeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  flex: {
    flex: 1,
  },
  syncButton: {
    borderRadius: 12,
  },
  buttonContent: {
    paddingVertical: 6,
  },
  input: {
    marginBottom: 12,
  },
  divider: {
    marginVertical: 8,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
  },
  logoutButton: {
    marginTop: 8,
    borderRadius: 12,
    borderColor: '#d93025',
  },
});
