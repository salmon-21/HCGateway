import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, Button, Card, useTheme } from 'react-native-paper';
import Toast from 'react-native-toast-message';
import { useAppState } from '../hooks/useAppState';

export default function SyncWarning({ onDismiss }) {
  const { updateFullSyncMode } = useAppState();
  const theme = useTheme();

  return (
    <Card style={[styles.card, { backgroundColor: '#fff8e1' }]} mode="outlined">
      <Card.Content>
        <Text variant="bodyMedium" style={styles.text}>
          Warning: Incremental sync only syncs data since the last sync.
          You may miss data if the app stops abruptly.
        </Text>
        <View style={styles.buttons}>
          <Button mode="text" onPress={onDismiss}>Cancel</Button>
          <Button
            mode="contained"
            onPress={async () => {
              await updateFullSyncMode(false);
              onDismiss();
              Toast.show({
                type: 'info',
                text1: 'Sync mode updated',
                text2: 'Will only sync data since last sync',
              });
            }}
          >
            Continue
          </Button>
        </View>
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {
    marginVertical: 8,
    borderRadius: 12,
    borderColor: '#ffcc02',
  },
  text: {
    color: '#6d4c00',
    marginBottom: 12,
  },
  buttons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 8,
  },
});
