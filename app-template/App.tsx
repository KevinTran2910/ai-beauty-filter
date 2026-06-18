/**
 * AI Beauty Filter — React Native demo
 *
 * Chế độ mặc định: chọn 1 ảnh từ thư viện → xử lý qua beauty filter →
 * điều chỉnh các thanh trượt để thấy kết quả thay đổi tức thì.
 *
 * Có thể chuyển sang chế độ Camera (real-time, chỉ chạy trên thiết bị thật).
 */
import React, { useCallback, useState } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  ScrollView,
} from 'react-native';
import Slider from '@react-native-community/slider';
import { launchImageLibrary } from 'react-native-image-picker';

import {
  GPUPixelImageView,
  GPUPixelCameraView,
  FilterParamProps,
} from './src';

type Mode = 'image' | 'camera';

const DEFAULTS: Required<FilterParamProps> = {
  smoothing: 6,
  whitening: 4,
  faceSlim: 3,
  eyeEnlarge: 3,
  blusher: 2,
};

const SLIDERS: { key: keyof FilterParamProps; label: string }[] = [
  { key: 'smoothing', label: 'Làm mịn' },
  { key: 'whitening', label: 'Trắng da' },
  { key: 'faceSlim', label: 'Thon mặt' },
  { key: 'eyeEnlarge', label: 'To mắt' },
  { key: 'blusher', label: 'Má hồng' },
];

export default function App() {
  const [mode, setMode] = useState<Mode>('image');
  const [imageUri, setImageUri] = useState<string | undefined>(undefined);
  const [params, setParams] = useState<Required<FilterParamProps>>(DEFAULTS);

  const pickImage = useCallback(async () => {
    const res = await launchImageLibrary({
      mediaType: 'photo',
      selectionLimit: 1,
    });
    const uri = res.assets?.[0]?.uri;
    if (uri) setImageUri(uri);
  }, []);

  const setParam = useCallback(
    (key: keyof FilterParamProps, value: number) =>
      setParams(p => ({ ...p, [key]: value })),
    [],
  );

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" />

      {/* Mode toggle */}
      <View style={styles.modeRow}>
        {(['image', 'camera'] as Mode[]).map(m => (
          <TouchableOpacity
            key={m}
            style={[styles.modeBtn, mode === m && styles.modeBtnActive]}
            onPress={() => setMode(m)}>
            <Text style={[styles.modeText, mode === m && styles.modeTextActive]}>
              {m === 'image' ? 'Ảnh' : 'Camera'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* Preview */}
      <View style={styles.preview}>
        {mode === 'image' ? (
          imageUri ? (
            <GPUPixelImageView
              style={styles.fill}
              imageUri={imageUri}
              smoothing={params.smoothing}
              whitening={params.whitening}
              faceSlim={params.faceSlim}
              eyeEnlarge={params.eyeEnlarge}
              blusher={params.blusher}
            />
          ) : (
            <View style={[styles.fill, styles.placeholder]}>
              <Text style={styles.placeholderText}>
                Chưa có ảnh — bấm “Chọn ảnh”
              </Text>
            </View>
          )
        ) : (
          <GPUPixelCameraView
            style={styles.fill}
            smoothing={params.smoothing}
            whitening={params.whitening}
            faceSlim={params.faceSlim}
            eyeEnlarge={params.eyeEnlarge}
            blusher={params.blusher}
          />
        )}
      </View>

      {/* Controls */}
      <ScrollView style={styles.controls} contentContainerStyle={styles.controlsContent}>
        {mode === 'image' && (
          <TouchableOpacity style={styles.pickBtn} onPress={pickImage}>
            <Text style={styles.pickBtnText}>Chọn ảnh</Text>
          </TouchableOpacity>
        )}

        {SLIDERS.map(({ key, label }) => (
          <View key={key} style={styles.sliderRow}>
            <Text style={styles.sliderLabel}>{label}</Text>
            <Slider
              style={styles.slider}
              minimumValue={0}
              maximumValue={10}
              step={0.1}
              value={params[key]}
              minimumTrackTintColor="#ff5c8a"
              maximumTrackTintColor="#555"
              thumbTintColor="#ff5c8a"
              onValueChange={v => setParam(key, v)}
            />
            <Text style={styles.sliderValue}>{params[key].toFixed(1)}</Text>
          </View>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#111' },
  fill: { flex: 1 },
  modeRow: { flexDirection: 'row', padding: 12, gap: 10 },
  modeBtn: {
    paddingVertical: 8,
    paddingHorizontal: 20,
    borderRadius: 20,
    backgroundColor: '#222',
  },
  modeBtnActive: { backgroundColor: '#ff5c8a' },
  modeText: { color: '#aaa', fontWeight: '600' },
  modeTextActive: { color: '#fff' },
  preview: { flex: 1, margin: 12, borderRadius: 12, overflow: 'hidden', backgroundColor: '#000' },
  placeholder: { alignItems: 'center', justifyContent: 'center' },
  placeholderText: { color: '#777', fontSize: 16 },
  controls: { maxHeight: 320 },
  controlsContent: { padding: 16, paddingBottom: 32 },
  pickBtn: {
    backgroundColor: '#ff5c8a',
    paddingVertical: 12,
    borderRadius: 10,
    alignItems: 'center',
    marginBottom: 16,
  },
  pickBtnText: { color: '#fff', fontWeight: '700', fontSize: 16 },
  sliderRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  sliderLabel: { color: '#ddd', width: 70, fontSize: 14 },
  slider: { flex: 1, height: 36 },
  sliderValue: { color: '#ff5c8a', width: 36, textAlign: 'right', fontVariant: ['tabular-nums'] },
});
