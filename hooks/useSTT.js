import { useState, useCallback, useEffect } from 'react';
import { useScribe } from '@elevenlabs/react';

/**
 * Custom hook for ElevenLabs Speech-to-Text (STT)
 * @param {Function} onTranscript - Callback function called when a transcript is committed
 */
export function useSTT(onTranscript) {
  const [isRecording, setIsRecording] = useState(false);
  const [language, setLanguage] = useState('jpn');

  // Load saved language from localStorage on mount and keep it in sync
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const syncLanguage = (e) => {
      if (e && e.key !== 'elevenlabs_language') return;
      const savedLang = localStorage.getItem('elevenlabs_language');
      if (savedLang) setLanguage(savedLang);
    };

    syncLanguage();

    window.addEventListener('storage', syncLanguage);

    return () => window.removeEventListener('storage', syncLanguage);
  }, []);

  const scribe = useScribe({
    modelId: 'scribe_v2_realtime',
    languageCode: language,
    onCommittedTranscript: (data) => {
      if (data.text && onTranscript) {
        // Append a space if there's already text, but the handling of appending
        // is done in the component using this hook.
        onTranscript(data.text);
      }
    },
    onSessionStarted: () => {
      setIsRecording(true);
    },
    onSessionEnded: () => {
      setIsRecording(false);
    },
    onError: (error) => {
      console.error('ElevenLabs Scribe error:', error);
      setIsRecording(false);
      // alert('音声入力エラー: ' + (error.message || '不明なエラー'));
    }
  });

  const toggleRecording = useCallback(async () => {
    if (scribe.isConnected) {
      try {
        await scribe.disconnect();
      } catch (e) {
        console.error('ElevenLabs disconnect error:', e);
      }
      setIsRecording(false);
      return;
    }

    const apiKey = localStorage.getItem('elevenlabs_api_key');
    if (!apiKey) {
      alert('ElevenLabs APIキーが設定されていません。「ミニアプリ」タブで設定してください。');
      return;
    }

    try {
      // Get single-use token via our API route to keep it "recommended"
      const response = await fetch('/api/elevenlabs/token/', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey }),
      });

      if (!response.ok) {
        let errorMessage = 'トークンの取得に失敗しました';
        try {
          const errorData = await response.json();
          errorMessage = errorData.detail?.message || errorData.detail || errorData.message || errorMessage;
          if (typeof errorMessage === 'object') errorMessage = JSON.stringify(errorMessage);
        } catch (e) {
          // If response is not JSON
        }
        throw new Error(errorMessage);
      }

      const { token } = await response.json();

      await scribe.connect({
        token,
        microphone: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
    } catch (error) {
      console.error('ElevenLabs STT start error:', error);
      alert('音声入力の開始に失敗しました: ' + error.message);
      setIsRecording(false);
    }
  }, [scribe, language]);

  return {
    isRecording: isRecording || scribe.isConnected,
    toggleRecording,
    isConnected: scribe.isConnected,
  };
}
