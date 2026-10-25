import { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as bridge from '../services/assistantBridge';
import type { CapabilityStatus } from '../services/assistantBridge';
import { ModelSettings } from './ModelSettings';

type Step = {
  key: 'overlay' | 'accessibility' | 'notifications';
  title: string;
  why: string;
  required: boolean;
  action: string;
  onPress: () => void;
  note?: string;
};

const STEPS: Step[] = [
  {
    key: 'overlay',
    title: 'Display over other apps',
    why: 'Lets the sloth bubble float above Chrome, Zepto, Amazon or any app you open, so you never have to switch back here.',
    required: true,
    action: 'Open setting',
    onPress: bridge.openOverlaySettings,
  },
  {
    key: 'accessibility',
    title: 'Accessibility service',
    why: 'Lets SlothAssist read the screen of the app you are using and tap, type, scroll and search for you. It only acts when you give it a command, and it never pays or places orders.',
    required: true,
    action: 'Open Accessibility',
    onPress: bridge.openAccessibilitySettings,
    note: 'Find "SlothAssist screen helper" under Installed apps / Downloaded apps. If it is greyed out ("Restricted setting"), open App info → ⋮ → Allow restricted settings, then try again.',
  },
  {
    key: 'notifications',
    title: 'Notifications',
    why: 'Android shows an ongoing notification while the bubble is active, with a quick "Turn off" button.',
    required: false,
    action: 'Allow',
    onPress: bridge.requestNotifications,
  },
];

export function OnboardingScreen() {
  const insets = useSafeAreaInsets();
  const [status, setStatus] = useState<CapabilityStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setStatus(await bridge.getStatus());
    } catch (e) {
      setError(String(e));
    }
  }, []);

  useEffect(() => {
    refresh();
    // Users grant permissions in system settings, so re-check whenever we come back.
    const sub = AppState.addEventListener('change', s => s === 'active' && refresh());
    const timer = setInterval(refresh, 2000);
    return () => {
      sub.remove();
      clearInterval(timer);
    };
  }, [refresh]);

  const ready = !!status?.overlay && !!status?.accessibility;

  const toggleBubble = async () => {
    setError(null);
    try {
      if (status?.bubbleRunning) bridge.stopBubble();
      else await bridge.startBubble();
    } catch (e: any) {
      setError(e?.message ?? String(e));
    }
    setTimeout(refresh, 400);
  };

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + 24, paddingBottom: insets.bottom + 32 }]}>
      <Text style={styles.logo}>🦥</Text>
      <Text style={styles.title}>SlothAssist</Text>
      <Text style={styles.subtitle}>
        Open any app, tap the floating sloth, and tell it what to do — "search for rice 1 kg
        and add 2 of the first option to the cart". It reads the screen and does the tapping for you, one step at a time.
      </Text>

      <Text style={styles.section}>Set up</Text>
      {STEPS.map(step => {
        const granted = !!status?.[step.key];
        return (
          <View key={step.key} style={[styles.card, granted && styles.cardDone]}>
            <View style={styles.cardHeader}>
              <Text style={styles.cardTitle}>{step.title}</Text>
              <Text style={[styles.badge, granted ? styles.badgeOk : step.required ? styles.badgeMissing : styles.badgeOptional]}>
                {granted ? 'On' : step.required ? 'Required' : 'Optional'}
              </Text>
            </View>
            <Text style={styles.cardBody}>{step.why}</Text>
            {!granted && step.note ? <Text style={styles.note}>{step.note}</Text> : null}
            {step.key === 'accessibility' && status?.accessibility && !status.accessibilityConnected ? (
              <Text style={styles.note}>Enabled, waiting for Android to connect the service…</Text>
            ) : null}
            {!granted ? (
              <Pressable accessibilityRole="button" style={styles.secondaryButton} onPress={step.onPress}>
                <Text style={styles.secondaryButtonText}>{step.action}</Text>
              </Pressable>
            ) : null}
          </View>
        );
      })}

      {!ready && status ? (
        <Text style={styles.missing}>
          The bubble can't work until {[!status.overlay && '"Display over other apps"', !status.accessibility && 'the accessibility service'].filter(Boolean).join(' and ')} {!status.overlay && !status.accessibility ? 'are' : 'is'} turned on.
          Granting one does not grant the other.
        </Text>
      ) : null}

      <Pressable
        accessibilityRole="button"
        disabled={!status?.overlay}
        style={[styles.primaryButton, !status?.overlay && styles.disabled]}
        onPress={toggleBubble}>
        <Text style={styles.primaryButtonText}>
          {status?.bubbleRunning ? 'Turn off floating sloth' : 'Start floating sloth'}
        </Text>
      </Pressable>
      {error ? <Text style={styles.error}>{error}</Text> : null}

      <Text style={styles.section}>Local AI model</Text>
      <ModelSettings />

      <Text style={styles.section}>What it can see</Text>
      <Text style={styles.cardBody}>
        Only the app on screen, only while a task runs. Screen contents and your commands go only to the
        open-weight model on your own computer — never to a cloud AI. Secure screens, logins, CAPTCHAs and payments are always
        left to you.
      </Text>
    </ScrollView>
  );
}

const C = {
  bg: '#FBF6EC',
  card: '#FFFFFF',
  ink: '#3B2F1E',
  muted: '#7A6A52',
  accent: '#8D6E4A',
  ok: '#2E7D32',
  bad: '#B3261E',
};

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg },
  content: { paddingHorizontal: 20 },
  logo: { fontSize: 56, textAlign: 'center' },
  title: { fontSize: 30, fontWeight: '800', color: C.ink, textAlign: 'center', marginTop: 4 },
  subtitle: { fontSize: 15, color: C.muted, textAlign: 'center', marginTop: 8, lineHeight: 21 },
  section: { fontSize: 13, fontWeight: '700', color: C.accent, textTransform: 'uppercase', letterSpacing: 1, marginTop: 28, marginBottom: 10 },
  card: { backgroundColor: C.card, borderRadius: 16, padding: 16, marginBottom: 12, borderWidth: 1, borderColor: '#EADFCB' },
  cardDone: { borderColor: '#B7D8B9' },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  cardTitle: { fontSize: 16, fontWeight: '700', color: C.ink, flexShrink: 1 },
  cardBody: { fontSize: 14, color: C.muted, marginTop: 6, lineHeight: 20 },
  note: { fontSize: 13, color: C.ink, marginTop: 8, lineHeight: 18, fontStyle: 'italic' },
  badge: { fontSize: 12, fontWeight: '700', paddingHorizontal: 10, paddingVertical: 3, borderRadius: 10, overflow: 'hidden' },
  badgeOk: { backgroundColor: '#E3F1E4', color: C.ok },
  badgeMissing: { backgroundColor: '#FBE4E2', color: C.bad },
  badgeOptional: { backgroundColor: '#F1EADD', color: C.muted },
  secondaryButton: { alignSelf: 'flex-start', marginTop: 12, paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, backgroundColor: '#F1EADD', minHeight: 44, justifyContent: 'center' },
  secondaryButtonText: { color: C.ink, fontWeight: '700' },
  missing: { color: C.bad, fontSize: 14, marginTop: 4, marginBottom: 4, lineHeight: 20 },
  primaryButton: { marginTop: 16, backgroundColor: C.accent, borderRadius: 14, paddingVertical: 16, alignItems: 'center', minHeight: 52 },
  primaryButtonText: { color: '#FFFFFF', fontSize: 16, fontWeight: '800' },
  disabled: { opacity: 0.4 },
  error: { color: C.bad, marginTop: 10 },
});
