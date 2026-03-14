import { MD3LightTheme, MD3DarkTheme } from 'react-native-paper';

export const createTheme = (materialTheme, isDark = false) => {
  const baseTheme = isDark ? MD3DarkTheme : MD3LightTheme;

  if (!materialTheme) return baseTheme;

  const scheme = isDark ? materialTheme.dark : materialTheme.light;

  if (!scheme) return baseTheme;

  return {
    ...baseTheme,
    version: 3,
    dark: isDark,
    colors: {
      ...baseTheme.colors,
      primary: scheme.primary,
      onPrimary: scheme.onPrimary,
      primaryContainer: scheme.primaryContainer,
      onPrimaryContainer: scheme.onPrimaryContainer,
      secondary: scheme.secondary,
      onSecondary: scheme.onSecondary,
      secondaryContainer: scheme.secondaryContainer,
      onSecondaryContainer: scheme.onSecondaryContainer,
      tertiary: scheme.tertiary,
      onTertiary: scheme.onTertiary,
      tertiaryContainer: scheme.tertiaryContainer,
      onTertiaryContainer: scheme.onTertiaryContainer,
      error: scheme.error,
      onError: scheme.onError,
      errorContainer: scheme.errorContainer,
      onErrorContainer: scheme.onErrorContainer,
      background: scheme.surfaceDim || scheme.background,
      onBackground: scheme.onBackground,
      surface: scheme.surface,
      onSurface: scheme.onSurface,
      surfaceVariant: scheme.surfaceContainerHighest || scheme.surfaceVariant,
      onSurfaceVariant: scheme.onSurfaceVariant,
      outline: scheme.outline,
      outlineVariant: scheme.outlineVariant,
      inverseSurface: scheme.inverseSurface,
      inverseOnSurface: scheme.inverseOnSurface,
      inversePrimary: scheme.inversePrimary,
      shadow: scheme.shadow,
      scrim: scheme.scrim,
      surfaceDisabled: scheme.surfaceDisabled,
      onSurfaceDisabled: scheme.onSurfaceDisabled,
      backdrop: scheme.backdrop,
      elevation: scheme.elevation || baseTheme.colors.elevation,
    },
  };
};

export default MD3LightTheme;
