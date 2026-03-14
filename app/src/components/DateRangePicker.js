import React, { useState } from 'react';
import { DatePickerModal } from 'react-native-paper-dates';

export default function DateRangePickerModal({
  visible,
  onClose,
  startDate,
  endDate,
  onStartDateChange,
  onEndDateChange,
  onApply,
}) {
  const onConfirm = ({ startDate: start, endDate: end }) => {
    if (start) onStartDateChange(start);
    if (end) onEndDateChange(end);
    onApply();
  };

  return (
    <DatePickerModal
      locale="en"
      mode="range"
      visible={visible}
      onDismiss={onClose}
      startDate={startDate instanceof Date ? startDate : new Date(startDate)}
      endDate={endDate instanceof Date ? endDate : new Date(endDate)}
      onConfirm={onConfirm}
      validRange={{ endDate: new Date() }}
    />
  );
}
