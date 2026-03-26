import React from 'react';
import {
  StyleSheet, Text, View, ScrollView,
  TouchableOpacity, Linking, Platform,
} from 'react-native';
import { SERVER_IP, SERVER_PORT } from '@/constants/server';

const BG      = '#0F0F13';
const CARD    = '#1A1A24';
const BORDER  = '#2A2A38';
const ACCENT  = '#A78BFA';
const PINK    = '#F472B6';

// ─── Step card ───────────────────────────────────────────────────────────────
function Step({ n, title, desc }: { n: string; title: string; desc: string }) {
  return (
    <View style={styles.stepCard}>
      <View style={styles.stepNum}>
        <Text style={styles.stepNumText}>{n}</Text>
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.stepTitle}>{title}</Text>
        <Text style={styles.stepDesc}>{desc}</Text>
      </View>
    </View>
  );
}

// ─── Tip card ────────────────────────────────────────────────────────────────
function Tip({ icon, text, color = ACCENT }: { icon: string; text: string; color?: string }) {
  return (
    <View style={[styles.tipCard, { borderLeftColor: color }]}>
      <Text style={styles.tipIcon}>{icon}</Text>
      <Text style={styles.tipText}>{text}</Text>
    </View>
  );
}

// ─── Section header ──────────────────────────────────────────────────────────
function Section({ title, color = ACCENT }: { title: string; color?: string }) {
  return <Text style={[styles.sectionTitle, { color }]}>{title}</Text>;
}

// ═════════════════════════════════════════════════════════════════════════════
export default function GuideScreen() {
  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.scroll}>

      {/* Hero */}
      <View style={styles.hero}>
        <Text style={styles.heroIcon}>📐</Text>
        <Text style={styles.heroTitle}>ArtGrid</Text>
        <Text style={styles.heroSub}>The Proportional Reference Tool</Text>
        <Text style={styles.heroBody}>
          ArtGrid replaces the traditional proportional divider.{'\n'}
          Upload a reference photo, pick your canvas size, and get{'\n'}
          real-world centimetre measurements in seconds.
        </Text>
      </View>

      {/* ── Face Mode ─────────────────────────────────────────────────── */}
      <View style={styles.modeRow}>
        <View style={[styles.modeBadge, { borderColor: ACCENT }]}>
          <Text style={styles.modeIcon}>😊</Text>
          <Text style={[styles.modeLabel, { color: ACCENT }]}>Face Mode</Text>
        </View>
        <View style={[styles.modeBadge, { borderColor: PINK }]}>
          <Text style={styles.modeIcon}>🧍</Text>
          <Text style={[styles.modeLabel, { color: PINK }]}>Body Mode</Text>
        </View>
      </View>

      {/* ── How to use ────────────────────────────────────────────────── */}
      <Section title="How to use — Face" />
      <Step n="1" title="Choose a reference photo"
        desc="Tap 🖼️ Gallery or 📷 Camera. Use a clear, well-lit photo facing forward." />
      <Step n="2" title="Select your canvas size"
        desc="Tap A4, A3, A5 or ✏️ Custom to enter your exact canvas width in cm." />
      <Step n="3" title="Calculate Proportions"
        desc="Hit 📏 Calculate. MediaPipe finds 478 facial landmarks and returns 13 measurements." />
      <Step n="4" title="Read your measurements"
        desc="All distances (face width, eye span, nose, mouth…) are shown in cm scaled to your canvas." />
      <Step n="5" title="Switch to Canvas View"
        desc="Tap 📐 Canvas for a technical diagram drawn to scale on your paper size — with a 1 cm grid." />
      <Step n="6" title="Save your annotated image"
        desc="Tap 💾 Save to Gallery to keep the overlay image as a drawing reference." />

      <Section title="How to use — Body" color={PINK} />
      <Step n="1" title="Open the Body tab"
        desc="Tap '🧍 Body' in the bottom bar. Works best with a full-body, front-facing photo." />
      <Step n="2" title="Detect Body Pose"
        desc="Uses MediaPipe Pose Landmarker (33 joints) to map shoulders, hips, knees, ankles, wrists." />
      <Step n="3" title="Read body measurements"
        desc="Get shoulder/hip width, torso length, arm segments, leg segments — all in cm at canvas scale." />
      <Step n="4" title="Use the stick figure canvas"
        desc="Canvas View shows a proportioned stick figure at scale — a blueprint for your figure drawing." />

      {/* ── What are ratios ───────────────────────────────────────────── */}
      <Section title="What are Proportion Ratios?" />
      <View style={styles.infoCard}>
        <Text style={styles.infoText}>
          Ratios are dimensionless — they don't change when you resize the canvas.{'\n\n'}
          For example, if the nose width is <Text style={{ color: ACCENT, fontWeight: '700' }}>0.88×</Text> the eye span, 
          it stays 0.88× whether you're drawing on A5 or A3.{'\n\n'}
          Use ratios to block in proportions on any canvas size, then use cm measurements to mark exact positions.
        </Text>
      </View>

      {/* ── Vertical thirds ───────────────────────────────────────────── */}
      <Section title="Vertical Thirds" />
      <View style={styles.infoCard}>
        <Text style={styles.infoText}>
          The face is divided into three zones:{'\n\n'}
          <Text style={{ color: '#60A5FA' }}>■ Forehead</Text>  hairline → brow{'\n'}
          <Text style={{ color: '#FBBF24' }}>■ Nose Zone</Text>  brow → nose tip{'\n'}
          <Text style={{ color: '#F472B6' }}>■ Lower Face</Text>  nose tip → chin{'\n\n'}
          The progress bars in the Ratios card show what % of face height each zone occupies.
          Use these to draw dividing lines across your canvas before adding details.
        </Text>
      </View>

      {/* ── Tips ──────────────────────────────────────────────────────── */}
      <Section title="Tips for best results" />
      <Tip icon="📸" text="Face should be looking directly at the camera (frontal view)." />
      <Tip icon="💡" text="Good even lighting — avoid harsh shadows across the face." />
      <Tip icon="🎨" text="For body mode, make sure the whole figure fits in the frame." />
      <Tip icon="🖼️" text="Use the highest quality photo you have — more pixels = more accurate." />
      <Tip icon="📐" color={PINK}
        text="Canvas View works best for portrait orientation canvases (taller than wide)." />
      <Tip icon="🔄" color={PINK}
        text="Change canvas size after analysis for an instant recalculate — no re-upload needed." />

      {/* ── Error Troubleshooting ─────────────────────────────────────── */}
      <Section title="Troubleshooting" />
      <View style={styles.errorCard}>
        <Text style={styles.errorRow}>
          <Text style={{ color: '#FF6B6B', fontWeight: '700' }}>No Face Detected</Text>
          {'  →  '}Try a clearer photo with the full face visible and good lighting.
        </Text>
      </View>
      <View style={styles.errorCard}>
        <Text style={styles.errorRow}>
          <Text style={{ color: '#FF6B6B', fontWeight: '700' }}>No Pose Detected</Text>
          {'  →  '}Use a full-body photo. Avoid images where limbs are cropped out.
        </Text>
      </View>
      <View style={styles.errorCard}>
        <Text style={styles.errorRow}>
          <Text style={{ color: '#FF6B6B', fontWeight: '700' }}>Connection Error</Text>
          {'  →  '}Make sure your PC and phone are on the same Wi-Fi. Check that FastAPI is running.
        </Text>
      </View>

      {/* ── Server info ───────────────────────────────────────────────── */}
      <Section title="Server" />
      <View style={[styles.infoCard, { backgroundColor: '#13131C' }]}>
        <Text style={{ color: '#777', fontSize: 11, marginBottom: 6 }}>Current server address</Text>
        <Text style={styles.serverText}>{`http://${SERVER_IP}:${SERVER_PORT}`}</Text>
        <Text style={{ color: '#555', fontSize: 11, marginTop: 8 }}>
          To change it, edit <Text style={{ color: ACCENT }}>constants/server.ts</Text> — one file, all screens update automatically.
        </Text>
      </View>

      <View style={{ height: 40 }} />
    </ScrollView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  root:   { flex: 1, backgroundColor: BG },
  scroll: { paddingTop: 60, paddingBottom: 40, paddingHorizontal: 20, gap: 10 },

  hero:     { alignItems: 'center', marginBottom: 8, gap: 4 },
  heroIcon: { fontSize: 48 },
  heroTitle:{ fontSize: 34, fontWeight: '900', color: '#fff', letterSpacing: 1 },
  heroSub:  { fontSize: 13, color: ACCENT, fontWeight: '600' },
  heroBody: { fontSize: 13, color: '#888', textAlign: 'center', marginTop: 6, lineHeight: 20 },

  modeRow:   { flexDirection: 'row', gap: 12, marginBottom: 4 },
  modeBadge: { flex: 1, borderWidth: 1, borderRadius: 14, padding: 14, alignItems: 'center', gap: 4, backgroundColor: CARD },
  modeIcon:  { fontSize: 28 },
  modeLabel: { fontSize: 14, fontWeight: '700' },

  sectionTitle: { fontSize: 16, fontWeight: '800', marginTop: 8, marginBottom: 4 },

  stepCard: { flexDirection: 'row', backgroundColor: CARD, borderRadius: 14, padding: 14, gap: 12, borderWidth: 1, borderColor: BORDER, alignItems: 'flex-start' },
  stepNum:  { width: 28, height: 28, borderRadius: 14, backgroundColor: 'rgba(167,139,250,0.15)', borderWidth: 1, borderColor: ACCENT, alignItems: 'center', justifyContent: 'center' },
  stepNumText: { color: ACCENT, fontWeight: '800', fontSize: 13 },
  stepTitle:   { color: '#fff', fontWeight: '700', fontSize: 14, marginBottom: 3 },
  stepDesc:    { color: '#888', fontSize: 12, lineHeight: 18 },

  tipCard: { flexDirection: 'row', gap: 10, backgroundColor: CARD, borderRadius: 12, padding: 12, borderWidth: 1, borderColor: BORDER, borderLeftWidth: 3, alignItems: 'flex-start' },
  tipIcon: { fontSize: 18, marginTop: 1 },
  tipText: { color: '#aaa', fontSize: 13, flex: 1, lineHeight: 18 },

  infoCard: { backgroundColor: CARD, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: BORDER },
  infoText: { color: '#999', fontSize: 13, lineHeight: 20 },

  errorCard: { backgroundColor: '#1A1010', borderRadius: 12, padding: 12, borderWidth: 1, borderColor: '#3A1A1A' },
  errorRow:  { color: '#aaa', fontSize: 13, lineHeight: 20 },

  serverText: { color: '#A78BFA', fontFamily: Platform.OS === 'ios' ? 'Courier New' : 'monospace', fontSize: 13, fontWeight: '700' },
});
