import React, { useState } from 'react';
import { View, ScrollView, StyleSheet, KeyboardAvoidingView, Platform } from 'react-native';
import { Text, TextInput, Button, Card, useTheme } from 'react-native-paper';
import M3Switch from '../components/M3Switch';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useAppState } from '../hooks/useAppState';

export default function LoginScreen() {
  const [form, setForm] = useState({});
  const { apiBase, sentryEnabled, doLogin, updateApiBase, updateSentryEnabled } = useAppState();
  const theme = useTheme();

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.colors.background }]}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.flex}
      >
        <ScrollView contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled">
          <View style={styles.header}>
            <Text variant="headlineLarge" style={styles.title}>HCGateway</Text>
            <Text variant="bodyLarge" style={{ color: theme.colors.onSurfaceVariant }}>
              Health Connect Sync
            </Text>
          </View>

          <Card style={styles.card} mode="contained">
            <Card.Content style={styles.cardContent}>
              <Text variant="titleMedium" style={styles.sectionTitle}>Login</Text>
              <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant, marginBottom: 16 }}>
                If you don't have an account, one will be created automatically.
              </Text>

              <TextInput
                label="Username"
                mode="outlined"
                onChangeText={(text) => setForm((prev) => ({ ...prev, username: text }))}
                style={[styles.input, { backgroundColor: 'transparent' }]}
                theme={{ colors: { background: theme.colors.surfaceVariant } }}
              />
              <TextInput
                label="Password"
                mode="outlined"
                secureTextEntry
                onChangeText={(text) => setForm((prev) => ({ ...prev, password: text }))}
                style={[styles.input, { backgroundColor: 'transparent' }]}
                theme={{ colors: { background: theme.colors.surfaceVariant } }}
              />
              <TextInput
                label="API Base URL"
                mode="outlined"
                defaultValue={apiBase}
                onChangeText={updateApiBase}
                style={[styles.input, { backgroundColor: 'transparent' }]}
                theme={{ colors: { background: theme.colors.surfaceVariant } }}
              />

              <View style={styles.switchRow}>
                <Text variant="bodyLarge">Enable Sentry</Text>
                <M3Switch value={sentryEnabled} onValueChange={updateSentryEnabled} />
              </View>

              <Button
                mode="contained"
                onPress={() => doLogin(form)}
                style={styles.loginButton}
                contentStyle={styles.buttonContent}
              >
                Login
              </Button>
            </Card.Content>
          </Card>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  flex: {
    flex: 1,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    padding: 24,
  },
  header: {
    alignItems: 'center',
    marginBottom: 32,
  },
  title: {
    fontWeight: 'bold',
    marginBottom: 4,
  },
  card: {
    borderRadius: 16,
  },
  cardContent: {
    padding: 8,
  },
  sectionTitle: {
    fontWeight: '600',
    marginBottom: 4,
  },
  input: {
    marginBottom: 12,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    marginBottom: 8,
  },
  loginButton: {
    marginTop: 8,
    borderRadius: 12,
  },
  buttonContent: {
    paddingVertical: 6,
  },
});
