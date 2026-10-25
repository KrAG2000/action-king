import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import * as bridge from '../services/assistantBridge';
import type { AgentConfig, ModelCheck, Provider } from '../services/assistantBridge';

const PROVIDERS: { key: Provider; title: string; body: string }[] = [
  { key: 'ON_DEVICE', title: 'On this phone', body: 'Gemma 4 E2B · offline' },
  { key: 'OLLAMA', title: 'My computer', body: 'Ollama over USB / Wi-Fi' },
];

/** Which open-weight model plans the steps: Gemma inside the app, or Ollama on the user's computer. */
export function ModelSettings() {
  const [config, setConfig] = useState<AgentConfig | null>(null);
  const [check, setCheck] = useState<ModelCheck | null>(null);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    bridge.getAgentConfig().then(setConfig);
  }, []);

  if (!config) return null;

  const update = (patch: Partial<AgentConfig>) => {
    const next = { ...config, ...patch };
    setConfig(next);
    setCheck(null);
    bridge.setAgentConfig(next);
  };

  const test = async () => {
    setTesting(true);
    setCheck(null);
    try {
      setCheck(await bridge.testModel());
      setConfig(await bridge.getAgentConfig());
    } finally {
      setTesting(false);
    }
  };

  const onDevice = config.provider === 'ON_DEVICE';

  return (
    <View style={styles.card}>
      <View style={styles.segment} accessibilityRole="radiogroup">
        {PROVIDERS.map(p => {
          const selected = p.key === config.provider;
          return (
            <Pressable
              key={p.key}
              accessibilityRole="radio"
              accessibilityState={{ selected }}
              style={[styles.option, selected && styles.optionSelected]}
              onPress={() => update({ provider: p.key })}>
              <Text style={[styles.optionTitle, selected && styles.optionTitleSelected]}>{p.title}</Text>
              <Text style={styles.optionBody}>{p.body}</Text>
            </Pressable>
          );
        })}
      </View>

      {onDevice ? (
        <>
          <Text style={[styles.status, config.modelFileExists ? styles.ok : styles.bad]}>
            {config.modelFileExists ? 'Model file found ✓' : 'Model file not found yet'}
          </Text>
          <Text style={styles.help}>
            Gemma runs inside SlothAssist. Nothing leaves the phone, and it works with no internet.
            {config.modelFileExists ? '' : ' Copy the model here with adb:'}
          </Text>
          {!config.modelFileExists ? (
            <Text selectable style={styles.codeBlock}>
              {config.modelPath}
            </Text>
          ) : null}
        </>
      ) : (
        <>
          <Text style={styles.label}>Model server</Text>
          <TextInput
            style={styles.input}
            value={config.serverUrl}
            onChangeText={serverUrl => update({ serverUrl })}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            accessibilityLabel="Model server address"
          />
          <Text style={styles.label}>Model</Text>
          <TextInput
            style={styles.input}
            value={config.model}
            onChangeText={model => update({ model })}
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Model name"
          />
          <Text style={styles.help}>
            Over USB, run <Text style={styles.code}>adb reverse tcp:11434 tcp:11434</Text> once and keep the
            default address.
          </Text>
        </>
      )}

      <Pressable accessibilityRole="button" style={styles.button} onPress={test} disabled={testing}>
        {testing ? (
          <ActivityIndicator color="#3B2F1E" />
        ) : (
          <Text style={styles.buttonText}>{onDevice ? 'Load & test Gemma' : 'Test connection'}</Text>
        )}
      </Pressable>
      {testing && onDevice ? <Text style={styles.help}>Loading the model, this can take a few seconds…</Text> : null}
      {check ? <Text style={[styles.status, check.ok ? styles.ok : styles.bad]}>{check.message}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { backgroundColor: '#FFFFFF', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: '#EADFCB' },
  segment: { flexDirection: 'row', gap: 8, marginBottom: 12 },
  option: { flex: 1, borderWidth: 1, borderColor: '#EADFCB', borderRadius: 12, padding: 10, minHeight: 56 },
  optionSelected: { borderColor: '#8D6E4A', backgroundColor: '#F7F0E3' },
  optionTitle: { fontSize: 14, fontWeight: '700', color: '#7A6A52' },
  optionTitleSelected: { color: '#3B2F1E' },
  optionBody: { fontSize: 12, color: '#7A6A52', marginTop: 2 },
  label: { fontSize: 13, fontWeight: '700', color: '#3B2F1E', marginTop: 4 },
  input: { borderWidth: 1, borderColor: '#EADFCB', borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, marginTop: 6, marginBottom: 10, color: '#3B2F1E', fontSize: 15, minHeight: 44 },
  help: { fontSize: 13, color: '#7A6A52', lineHeight: 19, marginTop: 4 },
  code: { fontFamily: 'monospace', color: '#3B2F1E' },
  codeBlock: { fontFamily: 'monospace', fontSize: 12, color: '#3B2F1E', backgroundColor: '#F7F0E3', padding: 8, borderRadius: 8, marginTop: 6 },
  button: { alignSelf: 'flex-start', marginTop: 12, paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, backgroundColor: '#F1EADD', minHeight: 44, minWidth: 160, alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#3B2F1E', fontWeight: '700' },
  status: { marginTop: 10, fontSize: 14, lineHeight: 20 },
  ok: { color: '#2E7D32' },
  bad: { color: '#B3261E' },
});
