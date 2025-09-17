import { yellow } from "./color";

export const noMicrophoneWarn = (funcName?: string, packageName?: string): string => {
  return yellow(`
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ⚠️  Warning: ${funcName ? funcName : "This function"} requires microphone permission.                      │
│                                                                                  │
│ Please enable the microphone permission in the developer portal at:              │
│   https://console.mentra.glass/apps/${packageName}/edit                          │
│                                                                                  │
│ under *Required Permissions*.                                                    │
└──────────────────────────────────────────────────────────────────────────────────┘
`);
};
