import { NativeModules, PermissionsAndroid, Platform } from 'react-native';

export type CapabilityStatus = {
  overlay: boolean;
  accessibility: boolean;
  accessibilityConnected: boolean;
  bubbleRunning: boolean;
  notifications: boolean;
};

export type AgentConfig = { serverUrl: string; model: string };

export type ModelCheck = {
  reachable: boolean;
  modelInstalled: boolean;
  models?: string[];
  error?: string;
};

type NativeBridge = {
  getAgentConfig(): Promise<AgentConfig>;
  setAgentConfig(serverUrl: string, model: string): void;
  testModel(): Promise<ModelCheck>;
  getStatus(): Promise<Omit<CapabilityStatus, 'notifications'>>;
  openOverlaySettings(): void;
  openAccessibilitySettings(): void;
  openAppSettings(): void;
  startBubble(): Promise<boolean>;
  stopBubble(): void;
};

const native: NativeBridge = NativeModules.AssistantBridge;

const needsNotificationPermission =
  Platform.OS === 'android' && Number(Platform.Version) >= 33;

export async function getStatus(): Promise<CapabilityStatus> {
  const s = await native.getStatus();
  const notifications = needsNotificationPermission
    ? await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS)
    : true;
  return { ...s, notifications };
}

export async function requestNotifications(): Promise<void> {
  if (!needsNotificationPermission) return;
  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  );
  if (result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) native.openAppSettings();
}

export const openOverlaySettings = () => native.openOverlaySettings();
export const openAccessibilitySettings = () => native.openAccessibilitySettings();
export const openAppSettings = () => native.openAppSettings();
export const startBubble = () => native.startBubble();
export const stopBubble = () => native.stopBubble();
export const getAgentConfig = () => native.getAgentConfig();
export const setAgentConfig = (c: AgentConfig) => native.setAgentConfig(c.serverUrl, c.model);
export const testModel = () => native.testModel();
