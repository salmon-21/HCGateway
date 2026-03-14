import React from 'react';
import { View, StyleSheet } from 'react-native';
import { useTheme } from 'react-native-paper';

const CONTAINER_MAP = {
  lowest: 'surfaceContainerLowest',
  low: 'surfaceContainerLow',
  default: 'surfaceContainer',
  high: 'surfaceContainerHigh',
  highest: 'surfaceContainerHighest',
};

export default function Surface({ children, container, style }) {
  const theme = useTheme();
  // Light: use 'low' for subtle contrast against white background
  // Dark: use 'high' for visible contrast against dark background
  const defaultContainer = theme.dark ? 'high' : 'low';
  const level = container || defaultContainer;
  const tokenKey = CONTAINER_MAP[level] || CONTAINER_MAP.default;
  const backgroundColor = theme.colors[tokenKey] || theme.colors.elevation.level1;

  return (
    <View style={[styles.surface, { backgroundColor }, style]}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  surface: {
    borderRadius: 12,
    padding: 16,
  },
});
