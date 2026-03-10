import React, { useState } from 'react';
import { 
  StyleSheet, 
  Text, 
  View, 
  Image, 
  TouchableOpacity, 
  ActivityIndicator, 
  ScrollView 
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';

export default function HomeScreen() {
  const [selectedImage, setSelectedImage] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [responseData, setResponseData] = useState<any>(null);

  // 1. Pick Image
  const pickImageAsync = async () => {
    let result = await ImagePicker.launchImageLibraryAsync({
      allowsEditing: true,
      quality: 1,
    });

    if (!result.canceled) {
      setSelectedImage(result.assets[0].uri);
      setResponseData(null); // Clear old results when picking a new image
    }
  };

  // 2. The Bridge (Send to FastAPI)
  const uploadImage = async () => {
    if (!selectedImage) return;

    setIsProcessing(true);

    // Create a form data object to send the file
    let formData = new FormData();
    
    // In React Native, we format the URI for the backend like this
    let localUri = selectedImage;
    let filename = localUri.split('/').pop() || 'photo.jpg';
    let match = /\.(\w+)$/.exec(filename);
    let type = match ? `image/${match[1]}` : `image/jpeg`;

    formData.append('file', {
      uri: localUri,
      name: filename,
      type: type
    } as any);

    try {
      // NOTE: 10.0.2.2 is the standard loopback IP for Android Emulators.
      // If using Expo Go on a REAL phone, replace this with your computer's local Wi-Fi IP address (e.g., http://192.168.x.x:8000/process-image/)
      const SERVER_URL = 'http://172.27.128.1:8000/process-image/'; 
      
      const response = await fetch(SERVER_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'multipart/form-data',
        },
        body: formData,
      });

      const jsonResult = await response.json();
      setResponseData(jsonResult);
      
    } catch (error) {
      console.error('Error uploading image:', error);
      alert('Failed to connect to the server. Is FastAPI running?');
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.scrollContainer}>
      <View style={styles.header}>
        <Text style={styles.title}>Reference Guide</Text>
      </View>

      <View style={styles.imageContainer}>
        {selectedImage ? (
          <Image source={{ uri: selectedImage }} style={styles.image} />
        ) : (
          <Text style={styles.placeholderText}>Your canvas is empty.</Text>
        )}
      </View>

      <View style={styles.controlsContainer}>
        <TouchableOpacity style={styles.buttonSecondary} onPress={pickImageAsync}>
          <Text style={styles.buttonTextSecondary}>
            {selectedImage ? 'Change Photo' : 'Choose Photo'}
          </Text>
        </TouchableOpacity>

        {selectedImage && (
          <TouchableOpacity 
            style={[styles.buttonPrimary, isProcessing && styles.buttonDisabled]} 
            onPress={uploadImage}
            disabled={isProcessing}
          >
            {isProcessing ? (
               <ActivityIndicator color="#fff" />
            ) : (
               <Text style={styles.buttonTextPrimary}>Calculate Proportions</Text>
            )}
          </TouchableOpacity>
        )}
      </View>

      {/* 3. Display Results */}
      {responseData && (
        <View style={styles.resultContainer}>
          <Text style={styles.resultTitle}>Data Received:</Text>
          <Text style={styles.resultText}>Face Detected: {responseData.face_detected ? 'Yes' : 'No'}</Text>
          <Text style={styles.resultText}>Points Found: {responseData.landmarks?.length || 0}</Text>
          {/* We only show a snippet of the raw JSON because 468 landmarks is huge */}
          <Text style={styles.codeSnippet}>
             {JSON.stringify(responseData.landmarks?.slice(0, 2), null, 2)}...
          </Text>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scrollContainer: {
    flexGrow: 1,
    backgroundColor: '#121212',
    alignItems: 'center',
    paddingVertical: 60,
    paddingHorizontal: 20,
  },
  header: {
    alignItems: 'center',
    marginBottom: 20,
  },
  title: {
    color: '#ffffff',
    fontSize: 24,
    fontWeight: 'bold',
    letterSpacing: 1,
  },
  imageContainer: {
    width: '100%',
    height: 350,
    backgroundColor: '#1e1e1e',
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    marginBottom: 20,
  },
  image: {
    width: '100%',
    height: '100%',
    resizeMode: 'contain',
  },
  placeholderText: {
    color: '#888888',
    fontSize: 16,
  },
  controlsContainer: {
    width: '100%',
    gap: 12, // Space between buttons
  },
  buttonPrimary: {
    backgroundColor: '#4CAF50', // Green for action
    paddingVertical: 16,
    borderRadius: 30,
    alignItems: 'center',
  },
  buttonSecondary: {
    backgroundColor: '#333333', // Dark grey for secondary
    paddingVertical: 16,
    borderRadius: 30,
    alignItems: 'center',
  },
  buttonDisabled: {
    backgroundColor: '#2E7D32', // Darker green when loading
    opacity: 0.7,
  },
  buttonTextPrimary: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
  buttonTextSecondary: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
  resultContainer: {
    marginTop: 30,
    width: '100%',
    backgroundColor: '#1e1e1e',
    padding: 20,
    borderRadius: 12,
  },
  resultTitle: {
    color: '#4CAF50',
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
  },
  resultText: {
    color: '#ffffff',
    fontSize: 14,
    marginBottom: 5,
  },
  codeSnippet: {
    color: '#A1CEDC',
    fontFamily: 'monospace',
    fontSize: 12,
    marginTop: 10,
    backgroundColor: '#000000',
    padding: 10,
    borderRadius: 6,
  }
});