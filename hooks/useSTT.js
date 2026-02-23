import { useState, useCallback, useEffect } from 'react';
import { useScribe } from '@elevenlabs/react';

/**
 * Custom hook for ElevenLabs Speech-to-Text (STT)
 * @param {Function} onTranscript - Callback function called when a transcript is committed
 */
export function useSTT(onTranscript) {
  const [isRecording, setIsRecording] = useState(false);
  const [language, setLanguage] = useState('jpn');

  // Load saved language from localStorage on mount
  useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedLang = localStorage.getItem('elevenlabs_language');
      if (savedLang) setLanguage(savedLang);
    }
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

    // Refresh language from localStorage before starting
    const currentLang = localStorage.getItem('elevenlabs_language') || 'jpn';
    if (currentLang !== language) {
      setLanguage(currentLang);
    }

    try {
      // Get single-use token via our API route to keep it "recommended"
      const response = await fetch('/api/elevenlabs/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.detail?.message || 'トークンの取得に失敗しました');
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
