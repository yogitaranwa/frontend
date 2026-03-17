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
  const [paperWidth, setPaperWidth] = useState<number>(21.0); 
  
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [responseData, setResponseData] = useState<any>(null);

  // 1. Pick Image (Crop Feature Restored)
  const pickImageAsync = async () => {
    try {
      let result = await ImagePicker.launchImageLibraryAsync({
        allowsEditing: true, // Restored the cropping feature!
        quality: 1,
      });

      if (!result.canceled) {
        setSelectedImage(result.assets[0].uri);
        setResponseData(null); 
      }
    } catch (error) {
      console.log("Error picking image:", error);
      alert("Something went wrong opening the gallery.");
    }
  };

  // 2. The Bridge 
  const uploadImage = async () => {
    if (!selectedImage) return;

    setIsProcessing(true);

    let formData = new FormData();
    let localUri = selectedImage;
    let filename = localUri.split('/').pop() || 'photo.jpg';
    let match = /\.(\w+)$/.exec(filename);
    let type = match ? `image/${match[1]}` : `image/jpeg`;

    formData.append('file', {
      uri: localUri,
      name: filename,
      type: type
    } as any);
    
    formData.append('paper_width_cm', paperWidth.toString());

    try {
      // WARNING: Make sure this matches your current Wi-Fi IP address!
      const SERVER_URL = 'http://172.22.103.50:8000/process-image/'; 
      
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
      alert('Failed to connect to the server. Is FastAPI running on the right IP?');
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.scrollContainer}>
      <View style={styles.header}>
        <Text style={styles.title}>Reference Guide</Text>
      </View>

      {/* Canvas Area */}
      <View style={styles.imageContainer}>
        {selectedImage ? (
          <Image source={{ uri: selectedImage }} style={styles.image} />
        ) : (
          <Text style={styles.placeholderText}>Your canvas is empty.</Text>
        )}
      </View>

      {/* Paper Size Selector */}
      <View style={styles.sizeSelectorContainer}>
        <Text style={styles.sizeLabel}>Select Canvas Width:</Text>
        <View style={styles.sizeButtonsRow}>
          <TouchableOpacity 
            style={[styles.sizeButton, paperWidth === 14.8 && styles.sizeButtonActive]} 
            onPress={() => setPaperWidth(14.8)}
          >
            <Text style={[styles.sizeButtonText, paperWidth === 14.8 && styles.sizeButtonTextActive]}>A5</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.sizeButton, paperWidth === 21.0 && styles.sizeButtonActive]} 
            onPress={() => setPaperWidth(21.0)}
          >
            <Text style={[styles.sizeButtonText, paperWidth === 21.0 && styles.sizeButtonTextActive]}>A4</Text>
          </TouchableOpacity>

          <TouchableOpacity 
            style={[styles.sizeButton, paperWidth === 29.7 && styles.sizeButtonActive]} 
            onPress={() => setPaperWidth(29.7)}
          >
            <Text style={[styles.sizeButtonText, paperWidth === 29.7 && styles.sizeButtonTextActive]}>A3</Text>
          </TouchableOpacity>
        </View>
        <Text style={styles.currentSizeText}>Current Width: {paperWidth} cm</Text>
      </View>

      {/* Action Buttons */}
      <View style={styles.controlsContainer}>
        <TouchableOpacity style={styles.buttonSecondary} onPress={pickImageAsync}>
          <Text style={styles.buttonTextSecondary}>
            {selectedImage ? 'Change Photo' : 'Choose Photo'}
          </Text>
        </TouchableOpacity>

        {/* This is the missing step from your screenshot! It appears AFTER cropping */}
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

      {/* Results Box */}
      {responseData && (
        <View style={styles.resultContainer}>
          <Text style={styles.resultTitle}>Data Received:</Text>
          <Text style={styles.resultText}>Face Detected: {responseData.face_detected ? 'Yes' : 'No'}</Text>
          <Text style={styles.resultText}>Points Found: {responseData.landmarks?.length || 0}</Text>
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
  sizeSelectorContainer: {
    width: '100%',
    marginBottom: 30,
    alignItems: 'center',
  },
  sizeLabel: {
    color: '#ffffff',
    fontSize: 16,
    marginBottom: 10,
  },
  sizeButtonsRow: {
    flexDirection: 'row',
    gap: 15,
    marginBottom: 10,
  },
  sizeButton: {
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#444',
    backgroundColor: '#1e1e1e',
  },
  sizeButtonActive: {
    borderColor: '#4CAF50',
    backgroundColor: 'rgba(76, 175, 80, 0.2)',
  },
  sizeButtonText: {
    color: '#aaaaaa',
    fontSize: 16,
    fontWeight: '600',
  },
  sizeButtonTextActive: {
    color: '#4CAF50',
  },
  currentSizeText: {
    color: '#888888',
    fontSize: 14,
  },
  controlsContainer: {
    width: '100%',
    gap: 12,
  },
  buttonPrimary: {
    backgroundColor: '#4CAF50', 
    paddingVertical: 16,
    borderRadius: 30,
    alignItems: 'center',
  },
  buttonSecondary: {
    backgroundColor: '#333333', 
    paddingVertical: 16,
    borderRadius: 30,
    alignItems: 'center',
  },
  buttonDisabled: {
    backgroundColor: '#2E7D32', 
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