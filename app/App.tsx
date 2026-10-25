import { StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { OnboardingScreen } from './src/app/OnboardingScreen';

function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <OnboardingScreen />
    </SafeAreaProvider>
  );
}

export default App;
