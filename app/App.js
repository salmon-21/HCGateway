import React, { useMemo } from 'react';
import { StatusBar } from 'expo-status-bar';
import * as Sentry from '@sentry/react-native';
import Toast from 'react-native-toast-message';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { useMaterial3Theme } from '@pchmn/expo-material3-theme';
import { setupNotificationChannel, initSentry } from './src/services/notifications';
import { registerService } from './src/services/foregroundService';
import { AppStateProvider, useAppState } from './src/hooks/useAppState';
import LoginScreen from './src/screens/LoginScreen';
import HomeScreen from './src/screens/HomeScreen';
import { createTheme } from './src/theme';
import { en, registerTranslation } from 'react-native-paper-dates';

registerTranslation('en', en);
setupNotificationChannel();
initSentry();
registerService();

function AppContent() {
  const { login } = useAppState();
  return login ? <HomeScreen /> : <LoginScreen />;
}

export default Sentry.wrap(function App() {
  const { theme: materialTheme } = useMaterial3Theme();
  const theme = useMemo(() => createTheme(materialTheme), [materialTheme]);

  return (
    <SafeAreaProvider>
      <PaperProvider theme={theme}>
        <AppStateProvider>
          <AppContent />
          <StatusBar style="dark" />
          <Toast />
        </AppStateProvider>
      </PaperProvider>
    </SafeAreaProvider>
  );
});
