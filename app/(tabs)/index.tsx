import React, { useState } from 'react';
import {
  StyleSheet, Text, View, Image,
  TouchableOpacity, ActivityIndicator,
  ScrollView, Dimensions,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';

// ─── Constants ────────────────────────────────────────────────────────────────
const SERVER_URL = 'http://10.254.151.50:8000/process-image/';
const ACCENT     = '#A78BFA';
const BG         = '#0F0F13';
const CARD_BG    = '#1A1A24';
const BORDER     = '#2A2A38';

const CANVAS_SIZES = [
  { label: 'A5', value: 14.8 },
  { label: 'A4', value: 21.0 },
  { label: 'A3', value: 29.7 },
];

// ─── Types ────────────────────────────────────────────────────────────────────
interface Measurements {
  face_width_cm: number;      face_height_cm: number;
  eye_to_eye_cm: number;      left_eye_width_cm: number;  right_eye_width_cm: number;
  nose_width_cm: number;      nose_length_cm: number;
  mouth_width_cm: number;     lip_height_cm: number;
  forehead_to_brow_cm: number; brow_to_nose_tip_cm: number;
  nose_tip_to_chin_cm: number; eye_to_mouth_cm: number;
}
interface ApiResponse {
  face_detected:   boolean;
  canvas_width_cm: number;
  measurements:    Measurements;
  annotated_image?: string;   // base64 JPEG from backend OpenCV
  error?: string;
}

// ─── Measurement display groups ───────────────────────────────────────────────
const MEASURE_GROUPS = [
  { title: 'Face Frame', icon: '🖼️', color: '#60A5FA', items: [
    { key: 'face_width_cm',  label: 'Face Width'  },
    { key: 'face_height_cm', label: 'Face Height' },
  ]},
  { title: 'Eyes', icon: '👁️', color: '#34D399', items: [
    { key: 'eye_to_eye_cm',       label: 'Eye-to-Eye (outer)' },
    { key: 'left_eye_width_cm',   label: 'Left Eye Width'     },
    { key: 'right_eye_width_cm',  label: 'Right Eye Width'    },
  ]},
  { title: 'Nose', icon: '👃', color: '#FBBF24', items: [
    { key: 'nose_width_cm',  label: 'Nose Width'  },
    { key: 'nose_length_cm', label: 'Nose Length' },
  ]},
  { title: 'Mouth', icon: '👄', color: '#F472B6', items: [
    { key: 'mouth_width_cm', label: 'Mouth Width' },
    { key: 'lip_height_cm',  label: 'Lip Height'  },
  ]},
  { title: 'Vertical Distances', icon: '📏', color: '#C084FC', items: [
    { key: 'forehead_to_brow_cm', label: 'Forehead → Brow'  },
    { key: 'brow_to_nose_tip_cm', label: 'Brow → Nose Tip'  },
    { key: 'nose_tip_to_chin_cm', label: 'Nose Tip → Chin'  },
    { key: 'eye_to_mouth_cm',     label: 'Eye → Mouth'      },
  ]},
];

// ─── Component ────────────────────────────────────────────────────────────────
export default function HomeScreen() {
  const [selectedImage, setSelectedImage] = useState<string | null>(null);
  const [paperWidth,    setPaperWidth]    = useState<number>(21.0);
  const [isProcessing,  setIsProcessing]  = useState<boolean>(false);
  const [result,        setResult]        = useState<ApiResponse | null>(null);
  const [showOverlay,   setShowOverlay]   = useState<boolean>(true);

  // ── Pick photo ──────────────────────────────────────────────────────────────
  const pickImage = async () => {
    try {
      const res = await ImagePicker.launchImageLibraryAsync({
        allowsEditing: true, quality: 1,
      });
      if (!res.canceled) {
        setSelectedImage(res.assets[0].uri);
        setResult(null);
        setShowOverlay(true);
      }
    } catch { alert('Could not open gallery.'); }
  };

  // ── Upload + analyse ────────────────────────────────────────────────────────
  const analyse = async () => {
    if (!selectedImage) return;
    setIsProcessing(true);
    const formData = new FormData();
    const filename = selectedImage.split('/').pop() || 'photo.jpg';
    const ext      = /\.(\w+)$/.exec(filename);
    const type     = ext ? `image/${ext[1]}` : 'image/jpeg';
    formData.append('file', { uri: selectedImage, name: filename, type } as any);
    formData.append('paper_width_cm', paperWidth.toString());
    try {
      const res = await fetch(SERVER_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'multipart/form-data' },
        body: formData,
      });
      setResult(await res.json());
    } catch {
      alert('Failed to connect to the server. Is FastAPI running?');
    } finally {
      setIsProcessing(false);
    }
  };

  // ── Which image to show ─────────────────────────────────────────────────────
  const displayUri =
    showOverlay && result?.annotated_image
      ? `data:image/jpeg;base64,${result.annotated_image}`
      : selectedImage ?? undefined;

  // ── Render ──────────────────────────────────────────────────────────────────
  return (
    <ScrollView contentContainerStyle={styles.scroll}>

      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.appName}>ArtGrid</Text>
        <Text style={styles.subtitle}>Proportional Reference Tool</Text>
      </View>

      {/* Image card */}
      <View style={styles.imageCard}>
        {displayUri ? (
          <>
            <Image
              source={{ uri: displayUri }}
              style={styles.image}
              resizeMode="contain"
            />
            {/* Overlay toggle — only after analysis */}
            {result?.face_detected && (
              <TouchableOpacity
                style={styles.toggleBtn}
                onPress={() => setShowOverlay(v => !v)}
              >
                <Text style={styles.toggleBtnText}>
                  {showOverlay ? '🙈  Hide Overlay' : '👁️  Show Overlay'}
                </Text>
              </TouchableOpacity>
            )}
          </>
        ) : (
          <View style={styles.placeholder}>
            <Text style={styles.placeholderIcon}>🖼️</Text>
            <Text style={styles.placeholderText}>No reference photo selected</Text>
            <Text style={styles.placeholderHint}>Tap "Choose Photo" to get started</Text>
          </View>
        )}
      </View>

      {/* Canvas size */}
      <View style={styles.sectionCard}>
        <Text style={styles.sectionLabel}>📐  Physical Canvas Size</Text>
        <View style={styles.sizeRow}>
          {CANVAS_SIZES.map(s => (
            <TouchableOpacity
              key={s.label}
              style={[styles.sizeBtn, paperWidth === s.value && styles.sizeBtnActive]}
              onPress={() => setPaperWidth(s.value)}
            >
              <Text style={[styles.sizeBtnLabel, paperWidth === s.value && styles.sizeBtnLabelActive]}>
                {s.label}
              </Text>
              <Text style={[styles.sizeBtnSub, paperWidth === s.value && styles.sizeBtnLabelActive]}>
                {s.value} cm
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      {/* Buttons */}
      <View style={styles.actions}>
        <TouchableOpacity style={styles.btnSecondary} onPress={pickImage}>
          <Text style={styles.btnSecondaryText}>
            {selectedImage ? '📷  Change Photo' : '📷  Choose Photo'}
          </Text>
        </TouchableOpacity>

        {selectedImage && (
          <TouchableOpacity
            style={[styles.btnPrimary, isProcessing && styles.btnDisabled]}
            onPress={analyse}
            disabled={isProcessing}
          >
            {isProcessing
              ? <ActivityIndicator color="#fff" />
              : <Text style={styles.btnPrimaryText}>📏  Calculate Proportions</Text>
            }
          </TouchableOpacity>
        )}
      </View>

      {/* Results */}
      {result && (
        <View style={styles.resultsWrapper}>
          {!result.face_detected ? (
            <View style={styles.errorBox}>
              <Text style={styles.errorIcon}>😕</Text>
              <Text style={styles.errorTitle}>No Face Detected</Text>
              <Text style={styles.errorText}>
                Try a clearer, well-lit photo with a visible face.
              </Text>
            </View>
          ) : (
            <>
              <View style={styles.resultBanner}>
                <Text style={styles.resultBannerText}>
                  ✅  Measurements for {result.canvas_width_cm} cm wide canvas
                </Text>
              </View>

              {MEASURE_GROUPS.map(group => (
                <View key={group.title} style={[styles.groupCard, { borderLeftColor: group.color }]}>
                  <Text style={[styles.groupTitle, { color: group.color }]}>
                    {group.icon}  {group.title}
                  </Text>
                  {group.items.map(item => {
                    const val = (result.measurements as any)[item.key];
                    return (
                      <View key={item.key} style={styles.measureRow}>
                        <Text style={styles.measureLabel}>{item.label}</Text>
                        <View style={styles.measureValueBox}>
                          <Text style={[styles.measureValue, { color: group.color }]}>{val}</Text>
                          <Text style={styles.measureUnit}> cm</Text>
                        </View>
                      </View>
                    );
                  })}
                </View>
              ))}
            </>
          )}
        </View>
      )}

    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  scroll: {
    flexGrow: 1, backgroundColor: BG,
    paddingTop: 60, paddingBottom: 48, paddingHorizontal: 20, gap: 14,
  },

  header:   { alignItems: 'center', marginBottom: 2 },
  appName:  { fontSize: 30, fontWeight: '800', color: '#fff', letterSpacing: 1 },
  subtitle: { fontSize: 12, color: '#6B6B8A', marginTop: 3 },

  imageCard: {
    width: '100%', height: 340,
    backgroundColor: CARD_BG, borderRadius: 20, overflow: 'hidden',
    borderWidth: 1, borderColor: BORDER,
  },
  image: { width: '100%', height: '100%' },

  placeholder: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 6 },
  placeholderIcon: { fontSize: 40 },
  placeholderText: { color: '#888', fontSize: 14, fontWeight: '600' },
  placeholderHint: { color: '#555', fontSize: 12 },

  toggleBtn: {
    position: 'absolute', bottom: 10, right: 10,
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingHorizontal: 12, paddingVertical: 6,
    borderRadius: 20, borderWidth: 1, borderColor: 'rgba(255,255,255,0.15)',
  },
  toggleBtnText: { color: '#ddd', fontSize: 12, fontWeight: '600' },

  sectionCard: {
    backgroundColor: CARD_BG, borderRadius: 16, padding: 16,
    borderWidth: 1, borderColor: BORDER,
  },
  sectionLabel: { color: '#aaa', fontSize: 13, fontWeight: '600', marginBottom: 12 },

  sizeRow: { flexDirection: 'row', gap: 10 },
  sizeBtn: {
    flex: 1, alignItems: 'center', paddingVertical: 12, borderRadius: 12,
    borderWidth: 1, borderColor: BORDER, backgroundColor: '#13131C',
  },
  sizeBtnActive:      { borderColor: ACCENT, backgroundColor: 'rgba(167,139,250,0.12)' },
  sizeBtnLabel:       { color: '#aaa', fontSize: 16, fontWeight: '700' },
  sizeBtnLabelActive: { color: ACCENT },
  sizeBtnSub:         { color: '#555', fontSize: 11, marginTop: 2 },

  actions: { gap: 10 },
  btnPrimary: {
    backgroundColor: ACCENT, paddingVertical: 16, borderRadius: 14, alignItems: 'center',
  },
  btnSecondary: {
    backgroundColor: CARD_BG, paddingVertical: 16, borderRadius: 14,
    alignItems: 'center', borderWidth: 1, borderColor: BORDER,
  },
  btnDisabled:      { opacity: 0.6 },
  btnPrimaryText:   { color: '#fff', fontSize: 16, fontWeight: '700' },
  btnSecondaryText: { color: '#ccc', fontSize: 16, fontWeight: '600' },

  resultsWrapper: { gap: 12 },
  resultBanner: {
    backgroundColor: 'rgba(167,139,250,0.12)', borderRadius: 12, padding: 14,
    borderWidth: 1, borderColor: 'rgba(167,139,250,0.3)', alignItems: 'center',
  },
  resultBannerText: { color: ACCENT, fontWeight: '700', fontSize: 14 },

  groupCard: {
    backgroundColor: CARD_BG, borderRadius: 16, padding: 16,
    borderWidth: 1, borderColor: BORDER, borderLeftWidth: 3, gap: 10,
  },
  groupTitle: { fontSize: 15, fontWeight: '700', marginBottom: 2 },

  measureRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  measureLabel:    { color: '#888', fontSize: 13, flex: 1 },
  measureValueBox: { flexDirection: 'row', alignItems: 'baseline' },
  measureValue:    { fontSize: 20, fontWeight: '800' },
  measureUnit:     { color: '#666', fontSize: 12, fontWeight: '600' },

  errorBox: {
    backgroundColor: CARD_BG, borderRadius: 16, padding: 28,
    alignItems: 'center', gap: 8, borderWidth: 1, borderColor: '#3A1A1A',
  },
  errorIcon:  { fontSize: 36 },
  errorTitle: { color: '#FF6B6B', fontSize: 18, fontWeight: '700' },
  errorText:  { color: '#777', fontSize: 13, textAlign: 'center' },
});