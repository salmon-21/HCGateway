# HCGateway Development Guide

## Project Overview

Android app (React Native + Expo) that syncs Health Connect data to a self-hosted API server.

## Architecture

- **Android app**: `app/` — React Native 0.74.3 + Expo 51
- **API server**: `api/` — Python Flask

## Known Bug: formatDateToISOString timezone issue

**File**: `app/App.js` lines 547-551

```javascript
const formatDateToISOString = (date) => {
  if (!date) return null;
  const midnightDate = new Date(date);
  midnightDate.setHours(0, 0, 0, 0);  // Local timezone
  return midnightDate.toISOString();    // Converts to UTC → creates offset
};
```

- Only affects "SYNC SELECTED RANGE" (line 742), NOT auto/Full 30-day sync
- Auto sync (line 519) calls `sync()` without args → no bug
- Causes data gap equal to device's UTC offset
- GitHub Issue #52 reports same symptom

## Code Quality Issues (App.js — 908 lines)

1. Single-file monolith — UI, business logic, API calls all mixed
2. Global variables for state (`let login`, `let apiBase`, `let lastSync`)
3. `useReducer(x => x+1, 0)` forceUpdate hack
4. `setTimeout(j*3000)` for sync queue — unreliable async
5. Error handling is just `console.log(err)`
6. No component separation

## Refactoring Plan

1. Split App.js → screens / components / hooks / services
2. Replace global variables → React Context or Zustand
3. Fix sync queue (setTimeout → proper async/await)
4. Fix formatDateToISOString bug
5. UI redesign

## Development Setup

- **Dev workflow**: Expo Dev Client
  - Build Dev Client once: `npx expo run:android`
  - Then run `npx expo start --dev-client` for hot reload
  - JS changes don't require rebuild

## Conventions

- Communicate in Japanese
- Keep responses concise
