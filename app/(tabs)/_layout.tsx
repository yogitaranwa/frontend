import { Tabs } from 'expo-router';
import React from 'react';
import { Platform } from 'react-native';
import { HapticTab } from '@/components/haptic-tab';
import { IconSymbol } from '@/components/ui/icon-symbol';

const ACCENT_FACE  = '#A78BFA';
const ACCENT_BODY  = '#F472B6';
const ACCENT_GUIDE = '#34D399';
const INACTIVE     = '#4A4A6A';
const TAB_BG       = '#0F0F13';
const TAB_BORDER   = '#1A1A24';

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarButton: HapticTab,
        tabBarStyle: {
          backgroundColor: TAB_BG,
          borderTopColor: TAB_BORDER,
          borderTopWidth: 1,
          paddingBottom: Platform.OS === 'ios' ? 24 : 8,
          paddingTop: 8,
          height: Platform.OS === 'ios' ? 88 : 64,
        },
        tabBarInactiveTintColor: INACTIVE,
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'Face',
          tabBarActiveTintColor: ACCENT_FACE,
          tabBarIcon: ({ color }) => <IconSymbol size={26} name="face.smiling" color={color} />,
        }}
      />
      <Tabs.Screen
        name="body"
        options={{
          title: 'Body',
          tabBarActiveTintColor: ACCENT_BODY,
          tabBarIcon: ({ color }) => <IconSymbol size={26} name="figure.stand" color={color} />,
        }}
      />
      <Tabs.Screen
        name="explore"
        options={{
          title: 'Guide',
          tabBarActiveTintColor: ACCENT_GUIDE,
          tabBarIcon: ({ color }) => <IconSymbol size={26} name="info.circle" color={color} />,
        }}
      />
    </Tabs>
  );
}
