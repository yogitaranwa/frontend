import React, { useState, useCallback } from 'react';
import {
  StyleSheet, Text, View, Image,
  TouchableOpacity, ActivityIndicator,
  ScrollView, TextInput, Alert,
  KeyboardAvoidingView, Platform, Dimensions,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as MediaLibrary from 'expo-media-library';
import * as FileSystem from 'expo-file-system';

// ─── Constants ────────────────────────────────────────────────────────────────
const SERVER_URL  = 'http://10.254.151.50:8000/process-image/';
const RECALC_URL  = 'http://10.254.151.50:8000/recalculate/';
const ACCENT      = '#A78BFA';
const BG          = '#0F0F13';
const CARD_BG     = '#1A1A24';
const BORDER      = '#2A2A38';
const SCREEN_W    = Dimensions.get('window').width;

const PRESET_SIZES = [
  { label: 'A5', width: 14.8, height: 21.0 },
  { label: 'A4', width: 21.0, height: 29.7 },
  { label: 'A3', width: 29.7, height: 42.0 },
];

// ─── Types ────────────────────────────────────────────────────────────────────
interface Measurements { [key: string]: number }
interface Ratios        { [key: string]: number }
interface ApiResponse {
  face_detected:    boolean;
  canvas_width_cm:  number;
  canvas_height_cm?: number;
  measurements:     Measurements;
  ratios:           Ratios;
  measurements_px?: Record<string, number>;
  image_size?:      { width_px: number; height_px: number };
  annotated_image?: string;
  canvas_image?:    string;  // technical canvas diagram
}

// ─── Measurement groups ───────────────────────────────────────────────────────
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

// ─── Helpers ──────────────────────────────────────────────────────────────────
const pct   = (v?: number) => v != null ? `${Math.round(v * 100)}%` : '—';
const ratio  = (v?: number) => v != null ? `${v}×` : '—';

function FractionBar({ value, color }: { value: number; color: string }) {
  const W = (SCREEN_W - 40) * 0.45;
  return (
    <View style={{ width: W, height: 6, backgroundColor: '#2A2A38', borderRadius: 3, overflow: 'hidden' }}>
      <View style={{ width: W * Math.min(value, 1), height: 6, backgroundColor: color, borderRadius: 3 }} />
    </View>
  );
}

// ═══════════════════════════════════════════════════════════════════════════════
export default function HomeScreen() {
  const [selectedImage,   setSelectedImage]   = useState<string | null>(null);
  const [paperWidth,      setPaperWidth]      = useState<number>(21.0);
  const [paperHeight,     setPaperHeight]     = useState<number>(29.7);
  const [customWidth,     setCustomWidth]      = useState<string>('');
  const [useCustom,       setUseCustom]        = useState<boolean>(false);
  const [isProcessing,    setIsProcessing]    = useState<boolean>(false);
  const [isRecalculating, setIsRecalculating] = useState<boolean>(false);
  const [isSaving,        setIsSaving]        = useState<boolean>(false);
  const [result,          setResult]          = useState<ApiResponse | null>(null);
  const [viewMode,        setViewMode]        = useState<'photo' | 'canvas'>('photo');

  const activeWidth = useCustom ? (parseFloat(customWidth) || paperWidth) : paperWidth;

  // ── Gallery ──────────────────────────────────────────────────────────────────
  const pickFromGallery = async () => {
    try {
      const res = await ImagePicker.launchImageLibraryAsync({ allowsEditing: true, quality: 1 });
      if (!res.canceled) { setSelectedImage(res.assets[0].uri); setResult(null); }
    } catch { Alert.alert('Error', 'Could not open gallery.'); }
  };

  // ── Camera ───────────────────────────────────────────────────────────────────
  const openCamera = async () => {
    try {
      const perm = await ImagePicker.requestCameraPermissionsAsync();
      if (perm.status !== 'granted') {
        Alert.alert('Permission Needed', 'Please allow camera access in your settings.');
        return;
      }
      const res = await ImagePicker.launchCameraAsync({ allowsEditing: true, quality: 1 });
      if (!res.canceled) { setSelectedImage(res.assets[0].uri); setResult(null); }
    } catch { Alert.alert('Error', 'Could not open camera.'); }
  };

  // ── Analyse ──────────────────────────────────────────────────────────────────
  const analyse = async () => {
    if (!selectedImage) return;
    setIsProcessing(true);
    const formData = new FormData();
    const filename = selectedImage.split('/').pop() || 'photo.jpg';
    const ext      = /\.(\w+)$/.exec(filename);
    const type     = ext ? `image/${ext[1]}` : 'image/jpeg';
    formData.append('file', { uri: selectedImage, name: filename, type } as any);
    formData.append('paper_width_cm',  activeWidth.toString());
    formData.append('paper_height_cm', paperHeight.toString());
    try {
      const res = await fetch(SERVER_URL, {
        method: 'POST', headers: { 'Content-Type': 'multipart/form-data' }, body: formData,
      });
      setResult(await res.json());
      setViewMode('photo');
    } catch { Alert.alert('Connection Error', 'Failed to connect to the server. Is FastAPI running?'); }
    finally { setIsProcessing(false); }
  };

  // ── Recalculate ──────────────────────────────────────────────────────────────
  const recalculate = useCallback(async (newWidth: number) => {
    if (!result?.measurements_px || !result.image_size) return;
    setIsRecalculating(true);
    try {
      const res = await fetch(RECALC_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          measurements_px:  result.measurements_px,
          image_width_px:   result.image_size.width_px,
          paper_width_cm:   newWidth,
          paper_height_cm:  paperHeight,
          mode:             'face',
        }),
      });
      const json = await res.json();
      setResult(prev => prev ? { ...prev, ...json } : prev);
    } catch { Alert.alert('Error', 'Could not recalculate.'); }
    finally { setIsRecalculating(false); }
  }, [result, paperHeight]);

  const handleSizeChange = (newWidth: number, newHeight: number) => {
    setPaperWidth(newWidth);
    setPaperHeight(newHeight);
    setUseCustom(false);
    if (result?.face_detected) recalculate(newWidth);
  };

  const handleCustomConfirm = () => {
    const w = parseFloat(customWidth);
    if (!w || w < 1 || w > 200) { Alert.alert('Invalid', 'Enter a width between 1 and 200 cm.'); return; }
    if (result?.face_detected) recalculate(w);
  };

  // ── Save ─────────────────────────────────────────────────────────────────────
  const saveToGallery = async () => {
    if (!result?.annotated_image) return;
    setIsSaving(true);
    try {
      const { status } = await MediaLibrary.requestPermissionsAsync();
      if (status !== 'granted') { Alert.alert('Permission Denied', 'Allow gallery access in settings.'); return; }
      const fp = FileSystem.cacheDirectory + 'artgrid_face.jpg';
      await FileSystem.writeAsStringAsync(fp, result.annotated_image, { encoding: FileSystem.EncodingType.Base64 });
      await MediaLibrary.saveToLibraryAsync(fp);
      Alert.alert('✅ Saved!', 'Annotated image saved to your gallery.');
    } catch { Alert.alert('Error', 'Could not save image.'); }
    finally { setIsSaving(false); }
  };

  const displayUri = (() => {
    if (viewMode === 'canvas' && result?.canvas_image)
      return `data:image/jpeg;base64,${result.canvas_image}`;
    if (result?.annotated_image)
      return `data:image/jpeg;base64,${result.annotated_image}`;
    return selectedImage ?? undefined;
  })();

  const ratios = result?.ratios ?? {};

  // ─── Render ──────────────────────────────────────────────────────────────────
  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.scroll}>

        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.appName}>ArtGrid</Text>
          <Text style={styles.subtitle}>Face Proportions</Text>
        </View>

        {/* Image */}
        <View style={styles.imageCard}>
          {displayUri ? (
            <>
              <Image source={{ uri: displayUri }} style={styles.image} resizeMode="contain" />
              {result?.face_detected && (
                <View style={styles.viewModeRow}>
                  <TouchableOpacity
                    style={[styles.viewModeBtn, viewMode === 'photo' && styles.viewModeBtnActive]}
                    onPress={() => setViewMode('photo')}>
                    <Text style={[styles.viewModeBtnText, viewMode === 'photo' && { color: ACCENT }]}>📸 Photo</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.viewModeBtn, viewMode === 'canvas' && styles.viewModeBtnActive]}
                    onPress={() => setViewMode('canvas')}>
                    <Text style={[styles.viewModeBtnText, viewMode === 'canvas' && { color: ACCENT }]}>📐 Canvas</Text>
                  </TouchableOpacity>
                </View>
              )}
              {result?.annotated_image && (
                <TouchableOpacity style={[styles.saveBtn, isSaving && { opacity: 0.6 }]} onPress={saveToGallery} disabled={isSaving}>
                  {isSaving ? <ActivityIndicator color="#fff" size="small" /> : <Text style={styles.saveBtnText}>💾  Save to Gallery</Text>}
                </TouchableOpacity>
              )}
            </>
          ) : (
            <View style={styles.placeholder}>
              <Text style={styles.placeholderIcon}>🖼️</Text>
              <Text style={styles.placeholderText}>No reference photo</Text>
              <Text style={styles.placeholderHint}>Use the buttons below to add one</Text>
            </View>
          )}
        </View>

        {/* Canvas size */}
        <View style={styles.sectionCard}>
          <View style={styles.sectionLabelRow}>
            <Text style={styles.sectionLabel}>📐  Canvas Width</Text>
            {isRecalculating && (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                <ActivityIndicator color={ACCENT} size="small" />
                <Text style={{ color: ACCENT, fontSize: 12 }}>Updating…</Text>
              </View>
            )}
          </View>

          {/* Presets */}
          <View style={styles.sizeRow}>
            {PRESET_SIZES.map(s => (
              <TouchableOpacity
                key={s.label}
                style={[styles.sizeBtn, !useCustom && paperWidth === s.width && styles.sizeBtnActive]}
                onPress={() => handleSizeChange(s.width, s.height)}
              >
                <Text style={[styles.sizeBtnLabel, !useCustom && paperWidth === s.width && styles.sizeBtnLabelActive]}>{s.label}</Text>
                <Text style={[styles.sizeBtnSub,   !useCustom && paperWidth === s.width && styles.sizeBtnLabelActive]}>{s.width} cm</Text>
              </TouchableOpacity>
            ))}
            <TouchableOpacity
              style={[styles.sizeBtn, useCustom && styles.sizeBtnActive]}
              onPress={() => setUseCustom(true)}
            >
              <Text style={[styles.sizeBtnLabel, useCustom && styles.sizeBtnLabelActive]}>✏️</Text>
              <Text style={[styles.sizeBtnSub,   useCustom && styles.sizeBtnLabelActive]}>Custom</Text>
            </TouchableOpacity>
          </View>

          {/* Custom input */}
          {useCustom && (
            <View style={styles.customRow}>
              <TextInput
                style={styles.customInput}
                placeholder="Width in cm"
                placeholderTextColor="#555"
                keyboardType="decimal-pad"
                value={customWidth}
                onChangeText={setCustomWidth}
              />
              <TouchableOpacity style={styles.customConfirm} onPress={handleCustomConfirm}>
                <Text style={styles.customConfirmText}>Apply</Text>
              </TouchableOpacity>
            </View>
          )}
        </View>

        {/* Buttons */}
        <View style={styles.actions}>
          <View style={styles.photoRow}>
            <TouchableOpacity style={[styles.btnSecondary, { flex: 1 }]} onPress={pickFromGallery}>
              <Text style={styles.btnSecondaryText}>🖼️  Gallery</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btnSecondary, { flex: 1 }]} onPress={openCamera}>
              <Text style={styles.btnSecondaryText}>📷  Camera</Text>
            </TouchableOpacity>
          </View>
          {selectedImage && (
            <TouchableOpacity style={[styles.btnPrimary, isProcessing && styles.btnDisabled]} onPress={analyse} disabled={isProcessing}>
              {isProcessing ? <ActivityIndicator color="#fff" /> : <Text style={styles.btnPrimaryText}>📏  Calculate Proportions</Text>}
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
                <Text style={styles.errorText}>Try a clearer, well-lit photo with a visible face.</Text>
              </View>
            ) : (
              <>
                <View style={styles.resultBanner}>
                  <Text style={styles.resultBannerText}>✅  Results for {result.canvas_width_cm} cm wide canvas</Text>
                </View>

                {/* Ratios card */}
                <View style={[styles.groupCard, { borderLeftColor: '#E879F9' }]}>
                  <Text style={[styles.groupTitle, { color: '#E879F9' }]}>⚖️  Proportion Ratios</Text>
                  <Text style={{ color: '#555', fontSize: 11, marginBottom: 4 }}>Dimensionless — same on any canvas size.</Text>

                  <View style={styles.ratioRow}>
                    <View style={styles.ratioLabelCol}>
                      <Text style={styles.ratioLabel}>Face Aspect (H ÷ W)</Text>
                      <Text style={styles.ratioNote}>How many times taller than wide</Text>
                    </View>
                    <Text style={[styles.ratioValue, { color: '#E879F9' }]}>
                      {ratios.face_aspect_ratio != null ? `${ratios.face_aspect_ratio} : 1` : '—'}
                    </Text>
                  </View>

                  <View style={styles.divider} />
                  <Text style={styles.ratioSubhead}>Eyes</Text>
                  {[
                    { label: 'Eye Span / Face Width',   val: ratios.eye_span_to_face_width,  color: '#34D399' },
                    { label: 'Left Eye / Face Width',   val: ratios.left_eye_to_face_width,  color: '#34D399' },
                    { label: 'Right Eye / Face Width',  val: ratios.right_eye_to_face_width, color: '#34D399' },
                  ].map(({ label, val, color }) => (
                    <View key={label} style={styles.ratioRow}>
                      <Text style={[styles.ratioLabelCol, styles.ratioLabel]}>{label}</Text>
                      <Text style={[styles.ratioValue, { color }]}>{ratio(val)}</Text>
                    </View>
                  ))}

                  <View style={styles.divider} />
                  <Text style={styles.ratioSubhead}>Nose & Mouth vs Eye Span</Text>
                  {[
                    { label: 'Nose / Eye Span',   val: ratios.nose_to_eye_span,  color: '#FBBF24' },
                    { label: 'Mouth / Eye Span',  val: ratios.mouth_to_eye_span, color: '#F472B6' },
                  ].map(({ label, val, color }) => (
                    <View key={label} style={styles.ratioRow}>
                      <Text style={[styles.ratioLabelCol, styles.ratioLabel]}>{label}</Text>
                      <Text style={[styles.ratioValue, { color }]}>{ratio(val)}</Text>
                    </View>
                  ))}

                  <View style={styles.divider} />
                  <Text style={styles.ratioSubhead}>Vertical Thirds</Text>
                  <Text style={{ color: '#555', fontSize: 11, marginBottom: 8 }}>How face height divides into zones.</Text>
                  {[
                    { label: 'Forehead',   val: ratios.forehead_fraction,    color: '#60A5FA' },
                    { label: 'Nose Zone',  val: ratios.nose_zone_fraction,   color: '#FBBF24' },
                    { label: 'Lower Face', val: ratios.lower_face_fraction,  color: '#F472B6' },
                  ].map(({ label, val, color }) => (
                    <View key={label} style={styles.thirdRow}>
                      <Text style={[styles.thirdLabel, { color }]}>{label}</Text>
                      <FractionBar value={val ?? 0} color={color} />
                      <Text style={[styles.thirdPct, { color }]}>{pct(val)}</Text>
                    </View>
                  ))}
                </View>

                {/* cm measurement groups */}
                {MEASURE_GROUPS.map(group => (
                  <View key={group.title} style={[styles.groupCard, { borderLeftColor: group.color }]}>
                    <Text style={[styles.groupTitle, { color: group.color }]}>{group.icon}  {group.title}</Text>
                    {group.items.map(item => {
                      const val = result.measurements[item.key];
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
    </KeyboardAvoidingView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  scroll: { flexGrow: 1, backgroundColor: BG, paddingTop: 60, paddingBottom: 48, paddingHorizontal: 20, gap: 14 },

  header:   { alignItems: 'center', marginBottom: 2 },
  appName:  { fontSize: 30, fontWeight: '800', color: '#fff', letterSpacing: 1 },
  subtitle: { fontSize: 12, color: '#6B6B8A', marginTop: 3 },

  imageCard: { width: '100%', height: 340, backgroundColor: CARD_BG, borderRadius: 20, overflow: 'hidden', borderWidth: 1, borderColor: BORDER },
  image:     { width: '100%', height: '100%' },

  placeholder:     { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 6 },
  placeholderIcon: { fontSize: 40 },
  placeholderText: { color: '#888', fontSize: 14, fontWeight: '600' },
  placeholderHint: { color: '#555', fontSize: 12 },

  viewModeRow:         { position: 'absolute', top: 10, left: 10, right: 10, flexDirection: 'row', gap: 6, justifyContent: 'center' },
  viewModeBtn:         { flex: 1, paddingVertical: 6, borderRadius: 20, alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.55)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.1)' },
  viewModeBtnActive:   { borderColor: ACCENT, backgroundColor: 'rgba(167,139,250,0.2)' },
  viewModeBtnText:     { color: '#aaa', fontSize: 12, fontWeight: '700' },

  saveBtn:     { position: 'absolute', bottom: 10, left: 10, right: 10, backgroundColor: 'rgba(124,58,237,0.85)', paddingVertical: 10, borderRadius: 14, alignItems: 'center', borderWidth: 1, borderColor: 'rgba(167,139,250,0.4)' },
  saveBtnText: { color: '#fff', fontSize: 14, fontWeight: '700' },

  sectionCard:     { backgroundColor: CARD_BG, borderRadius: 16, padding: 16, borderWidth: 1, borderColor: BORDER },
  sectionLabelRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  sectionLabel:    { color: '#aaa', fontSize: 13, fontWeight: '600' },

  sizeRow: { flexDirection: 'row', gap: 8 },
  sizeBtn: { flex: 1, alignItems: 'center', paddingVertical: 10, borderRadius: 12, borderWidth: 1, borderColor: BORDER, backgroundColor: '#13131C' },
  sizeBtnActive:      { borderColor: ACCENT, backgroundColor: 'rgba(167,139,250,0.12)' },
  sizeBtnLabel:       { color: '#aaa', fontSize: 14, fontWeight: '700' },
  sizeBtnLabelActive: { color: ACCENT },
  sizeBtnSub:         { color: '#555', fontSize: 10, marginTop: 2 },

  customRow:        { flexDirection: 'row', gap: 10, marginTop: 12 },
  customInput:      { flex: 1, backgroundColor: '#13131C', borderWidth: 1, borderColor: BORDER, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 10, color: '#fff', fontSize: 15 },
  customConfirm:    { backgroundColor: ACCENT, paddingHorizontal: 18, paddingVertical: 10, borderRadius: 12, justifyContent: 'center' },
  customConfirmText:{ color: '#fff', fontWeight: '700', fontSize: 14 },

  actions:   { gap: 10 },
  photoRow:  { flexDirection: 'row', gap: 10 },
  btnPrimary:   { backgroundColor: ACCENT, paddingVertical: 16, borderRadius: 14, alignItems: 'center' },
  btnSecondary: { backgroundColor: CARD_BG, paddingVertical: 14, borderRadius: 14, alignItems: 'center', borderWidth: 1, borderColor: BORDER },
  btnDisabled:      { opacity: 0.6 },
  btnPrimaryText:   { color: '#fff', fontSize: 16, fontWeight: '700' },
  btnSecondaryText: { color: '#ccc', fontSize: 15, fontWeight: '600' },

  resultsWrapper: { gap: 12 },
  resultBanner:     { backgroundColor: 'rgba(167,139,250,0.12)', borderRadius: 12, padding: 14, borderWidth: 1, borderColor: 'rgba(167,139,250,0.3)', alignItems: 'center' },
  resultBannerText: { color: ACCENT, fontWeight: '700', fontSize: 14 },

  groupCard:  { backgroundColor: CARD_BG, borderRadius: 16, padding: 16, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 3, gap: 10 },
  groupTitle: { fontSize: 15, fontWeight: '700', marginBottom: 2 },

  ratioRow:     { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 },
  ratioLabelCol:{ flex: 1 },
  ratioLabel:   { color: '#ccc', fontSize: 13 },
  ratioNote:    { color: '#555', fontSize: 10, marginTop: 1 },
  ratioValue:   { fontSize: 17, fontWeight: '800', minWidth: 54, textAlign: 'right' },
  ratioSubhead: { color: '#777', fontSize: 12, fontWeight: '700', marginTop: 4 },
  divider:      { height: 1, backgroundColor: BORDER, marginVertical: 4 },

  thirdRow:   { flexDirection: 'row', alignItems: 'center', gap: 10, marginVertical: 3 },
  thirdLabel: { fontSize: 12, fontWeight: '600', width: 80 },
  thirdPct:   { fontSize: 13, fontWeight: '700', width: 36, textAlign: 'right' },

  measureRow:      { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  measureLabel:    { color: '#888', fontSize: 13, flex: 1 },
  measureValueBox: { flexDirection: 'row', alignItems: 'baseline' },
  measureValue:    { fontSize: 20, fontWeight: '800' },
  measureUnit:     { color: '#666', fontSize: 12, fontWeight: '600' },

  errorBox:  { backgroundColor: CARD_BG, borderRadius: 16, padding: 28, alignItems: 'center', gap: 8, borderWidth: 1, borderColor: '#3A1A1A' },
  errorIcon:  { fontSize: 36 },
  errorTitle: { color: '#FF6B6B', fontSize: 18, fontWeight: '700' },
  errorText:  { color: '#777', fontSize: 13, textAlign: 'center' },
});