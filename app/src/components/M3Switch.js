import React, { useEffect, useRef } from 'react';
import { Pressable, Animated, StyleSheet } from 'react-native';
import { useTheme } from 'react-native-paper';

// M3 spec: track 52x32, thumb 16 (off) / 24 (on), 2px border
const TRACK_WIDTH = 52;
const TRACK_HEIGHT = 32;
const BORDER_WIDTH = 2;
const THUMB_OFF = 16;
const THUMB_ON = 24;

// Inner width = track width minus borders on both sides
const INNER = TRACK_WIDTH - BORDER_WIDTH * 2;

// OFF: thumb centered vertically and horizontally in left position
// thumb left = (INNER - THUMB) / 2 when at left edge... but M3 spec:
// OFF: 8dp from left edge of track (center of 16dp thumb at x=8+8=16 from left)
// ON: 8dp from right edge of track
// Simplified: thumb margin from inner edge = (32 - 2*2 - thumbSize) / 2 = padding
const OFF_LEFT = (INNER - THUMB_OFF) / 2;                    // centered in left half
const ON_LEFT = INNER - THUMB_ON - (INNER - THUMB_ON) / 2;   // centered in right half

// Actually M3 spec is simpler: thumb hugs the side with equal padding
// OFF: left = (trackHeight - border*2 - thumbOff) / 2
// ON:  left = trackWidth - border*2 - thumbOn - (trackHeight - border*2 - thumbOn) / 2
const PADDING_OFF = (TRACK_HEIGHT - BORDER_WIDTH * 2 - THUMB_OFF) / 2; // = 6
const PADDING_ON = (TRACK_HEIGHT - BORDER_WIDTH * 2 - THUMB_ON) / 2;   // = 2

const LEFT_OFF = PADDING_OFF;
const LEFT_ON = TRACK_WIDTH - BORDER_WIDTH * 2 - THUMB_ON - PADDING_ON;

export default function M3Switch({ value, onValueChange, disabled }) {
  const theme = useTheme();
  const anim = useRef(new Animated.Value(value ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(anim, {
      toValue: value ? 1 : 0,
      duration: 150,
      useNativeDriver: false,
    }).start();
  }, [value]);

  const thumbSize = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [THUMB_OFF, THUMB_ON],
  });

  const thumbLeft = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [LEFT_OFF, LEFT_ON],
  });

  const thumbTop = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [PADDING_OFF, PADDING_ON],
  });

  const trackBg = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [theme.colors.surfaceVariant, theme.colors.primary],
  });

  const trackBorder = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [theme.colors.outline, theme.colors.primary],
  });

  const thumbBg = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [theme.colors.outline, theme.colors.onPrimary],
  });

  return (
    <Pressable
      onPress={() => !disabled && onValueChange?.(!value)}
      style={{ opacity: disabled ? 0.38 : 1 }}
    >
      <Animated.View
        style={[
          styles.track,
          { backgroundColor: trackBg, borderColor: trackBorder },
        ]}
      >
        <Animated.View
          style={{
            position: 'absolute',
            backgroundColor: thumbBg,
            width: thumbSize,
            height: thumbSize,
            borderRadius: THUMB_ON / 2,
            left: thumbLeft,
            top: thumbTop,
          }}
        />
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  track: {
    width: TRACK_WIDTH,
    height: TRACK_HEIGHT,
    borderRadius: TRACK_HEIGHT / 2,
    borderWidth: BORDER_WIDTH,
  },
});
