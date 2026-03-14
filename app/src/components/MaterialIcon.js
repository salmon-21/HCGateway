import React from 'react';
import SyncIcon from '@material-symbols/svg-700/outlined/sync.svg';
import LogoutIcon from '@material-symbols/svg-700/outlined/logout.svg';
import LightModeIcon from '@material-symbols/svg-700/outlined/light_mode.svg';
import DarkModeIcon from '@material-symbols/svg-700/outlined/dark_mode.svg';
import ContrastIcon from '@material-symbols/svg-700/outlined/contrast.svg';

const icons = {
  sync: SyncIcon,
  logout: LogoutIcon,
  'light-mode': LightModeIcon,
  'dark-mode': DarkModeIcon,
  contrast: ContrastIcon,
};

export const materialIcon = (name) => ({ size, color }) => {
  const Icon = icons[name];
  if (!Icon) return null;
  return <Icon width={size} height={size} fill={color} />;
};
