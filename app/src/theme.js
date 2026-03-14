import { MD3LightTheme, MD3DarkTheme } from 'react-native-paper';

export const createTheme = (materialTheme, isDark = false) => {
  const baseTheme = isDark ? MD3DarkTheme : MD3LightTheme;

  if (!materialTheme) return baseTheme;

  const scheme = isDark ? materialTheme.dark : materialTheme.light;

  return {
    ...baseTheme,
    version: 3,
    colors: {
      ...baseTheme.colors,
      ...scheme,
    },
  };
};

export default MD3LightTheme;
