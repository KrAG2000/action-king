import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import * as bridge from '../services/assistantBridge';
import type { AgentConfig, ModelCheck } from '../services/assistantBridge';

/** Where the open-weight model runs. Default: Ollama on the laptop, reached over USB. */
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
    try {
      setCheck(await bridge.testModel());
    } finally {
      setTesting(false);
    }
  };

  return (
    <View style={styles.card}>
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
        Runs on your own computer with Ollama. Over USB, run{' '}
        <Text style={styles.code}>adb reverse tcp:11434 tcp:11434</Text> once and keep the default address.
      </Text>
      <Pressable accessibilityRole="button" style={styles.button} onPress={test} disabled={testing}>
        {testing ? <ActivityIndicator color="#3B2F1E" /> : <Text style={styles.buttonText}>Test connection</Text>}
      </Pressable>
      {check ? (
        <Text style={[styles.result, check.reachable && check.modelInstalled ? styles.ok : styles.bad]}>
          {!check.reachable
            ? check.error
            : check.modelInstalled
              ? `Connected ✓ ${config.model} is ready.`
              : `Connected, but "${config.model}" isn't installed. Available: ${check.models?.join(', ') || 'none'}`}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { backgroundColor: '#FFFFFF', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: '#EADFCB' },
  label: { fontSize: 13, fontWeight: '700', color: '#3B2F1E', marginTop: 4 },
  input: { borderWidth: 1, borderColor: '#EADFCB', borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, marginTop: 6, marginBottom: 10, color: '#3B2F1E', fontSize: 15, minHeight: 44 },
  help: { fontSize: 13, color: '#7A6A52', lineHeight: 19 },
  code: { fontFamily: 'monospace', color: '#3B2F1E' },
  button: { alignSelf: 'flex-start', marginTop: 12, paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, backgroundColor: '#F1EADD', minHeight: 44, minWidth: 140, alignItems: 'center', justifyContent: 'center' },
  buttonText: { color: '#3B2F1E', fontWeight: '700' },
  result: { marginTop: 10, fontSize: 14, lineHeight: 20 },
  ok: { color: '#2E7D32' },
  bad: { color: '#B3261E' },
});
