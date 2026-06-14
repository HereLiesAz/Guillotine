import React, { useRef, useEffect, useState } from 'react';
import { TimelineClip, MediaFile, GlobalSettings } from '../types';
import { cn } from '../lib/utils';
import { Play, Pause, SkipBack, SkipForward, StepBack, StepForward, Loader2 } from 'lucide-react';

interface PlayerCanvasProps {
  mediaFiles: MediaFile[];
  clips: TimelineClip[];
  currentTime: number;
  isPlaying: boolean;
  onTogglePlay: () => void;
  globalSettings: GlobalSettings;
  onTimeChange: (time: number) => void;
  playbackRate: number;
  onPlaybackRateChange: (rate: number) => void;
}

export default function PlayerCanvas({ mediaFiles, clips, currentTime, isPlaying, onTogglePlay, globalSettings, onTimeChange, playbackRate, onPlaybackRateChange }: PlayerCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isBuffering, setIsBuffering] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  
  // Web Audio Context for panning
  const audioCtxRef = useRef<AudioContext | null>(null);
  const pannerRef = useRef<StereoPannerNode | null>(null);
  const gainNodeRef = useRef<GainNode | null>(null);
  const sourceNodeRef = useRef<MediaElementAudioSourceNode | null>(null);

  // Find active video
  const activeVideoClip = clips.find(c => c.type === 'video' && currentTime >= c.startTime && currentTime <= (c.startTime + c.duration));
  const activeAudioClip = clips.find(c => c.type === 'audio' && currentTime >= c.startTime && currentTime <= (c.startTime + c.duration));

  const activeVideoFile = activeVideoClip ? mediaFiles.find(m => m.id === activeVideoClip.mediaId) : null;
  const activeAudioFile = activeAudioClip ? mediaFiles.find(m => m.id === activeAudioClip.mediaId) : null;

  useEffect(() => {
      // Initialize only when user plays to avoid autoplay policies
      if (!audioCtxRef.current && isPlaying) {
          try {
             audioCtxRef.current = new (window.AudioContext || (window as any).webkitAudioContext)();
             if (audioRef.current) {
                sourceNodeRef.current = audioCtxRef.current.createMediaElementSource(audioRef.current);
                pannerRef.current = audioCtxRef.current.createStereoPanner();
                gainNodeRef.current = audioCtxRef.current.createGain();
                
                sourceNodeRef.current.connect(gainNodeRef.current);
                gainNodeRef.current.connect(pannerRef.current);
                pannerRef.current.connect(audioCtxRef.current.destination);
             }
          } catch (e) {
             console.error("Audio Context Init error: ", e);
          }
      }
      
      if (audioCtxRef.current && audioCtxRef.current.state === 'suspended' && isPlaying) {
          audioCtxRef.current.resume();
      }
  }, [isPlaying]);

  useEffect(() => {
      if (pannerRef.current) {
         pannerRef.current.pan.value = activeAudioClip?.filters?.pan ?? 0;
      }
      if (gainNodeRef.current) {
         // Apply mock normalization boost if enabled, plus volume
         let vol = activeAudioClip?.filters?.volume ?? 1;
         if (activeAudioClip?.filters?.normalize) {
            vol *= 1.5; // simplistic mock compression/normalization boost
         }
         gainNodeRef.current.gain.value = vol;
      }
  }, [activeAudioClip?.filters?.pan, activeAudioClip?.filters?.volume, activeAudioClip?.filters?.normalize]);

  // Aspect Ratio to CSS aspect-ratio
  const aspectMapping: Record<GlobalSettings['aspectRatio'], string> = {
    "16:9": "16 / 9",
    "9:16": "9 / 16",
    "1:1": "1 / 1",
    "Original": "auto"
  };

  const crop = globalSettings.crop;

  useEffect(() => {
     if (videoRef.current) {
        if (isPlaying) {
           videoRef.current.play().catch(() => {});
        } else {
           videoRef.current.pause();
        }
        videoRef.current.playbackRate = playbackRate;
     }
     if (audioRef.current) {
        if (isPlaying) {
           audioRef.current.play().catch(() => {});
        } else {
           audioRef.current.pause();
        }
        audioRef.current.playbackRate = playbackRate;
     }
  }, [isPlaying, playbackRate, activeVideoFile, activeAudioFile]);

  // Handle mock UI time scaling
  useEffect(() => {
     if (videoRef.current && activeVideoClip) {
        // Mock sync
        const targetTime = activeVideoClip.trimStart + (currentTime - activeVideoClip.startTime);
        if (Math.abs(videoRef.current.currentTime - targetTime) > 0.5) {
            videoRef.current.currentTime = targetTime;
        }
     }
     if (audioRef.current && activeAudioClip) {
        const targetTime = activeAudioClip.trimStart + (currentTime - activeAudioClip.startTime);
        if (Math.abs(audioRef.current.currentTime - targetTime) > 0.5) {
            audioRef.current.currentTime = targetTime;
        }
     }
  }, [currentTime, activeVideoClip, activeAudioClip]);

  return (
    <div className="flex-1 flex flex-col bg-black overflow-hidden relative">
      <div className="flex-1 flex items-center justify-center p-4 relative">
         {isBuffering && (
            <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm pointer-events-none">
               <div className="flex flex-col items-center gap-2">
                  <Loader2 className="w-8 h-8 text-red-500 animate-spin" />
                  <span className="text-xs text-neutral-300 font-medium tracking-tight">Buffering...</span>
               </div>
            </div>
         )}
         <div 
            className="flex items-center justify-center bg-neutral-900 border border-neutral-800 rounded shadow-2xl relative overflow-hidden"
            style={{ 
               aspectRatio: aspectMapping[globalSettings.aspectRatio],
               width: globalSettings.aspectRatio === '9:16' ? 'auto' : '100%',
               height: globalSettings.aspectRatio === '9:16' ? '100%' : 'auto',
               maxWidth: '100%',
               maxHeight: '100%'
            }}
         >
            {activeVideoFile ? (
               <div 
                  className="w-full h-full relative group" 
                  style={{
                     left: `${crop.x}%`,
                     top: `${crop.y}%`,
                     width: `${crop.w > 0 ? (10000 / crop.w) : 100}%`,
                     height: `${crop.h > 0 ? (10000 / crop.h) : 100}%`,
                     transformOrigin: 'top left'
                  }}
               >
                  <video 
                     ref={videoRef}
                     src={activeVideoFile.url} 
                     className="w-full h-full object-cover transition-all"
                     onWaiting={() => setIsBuffering(true)}
                     onPlaying={() => setIsBuffering(false)}
                     onCanPlay={() => setIsBuffering(false)}
                     muted={activeVideoClip?.filters?.volume === 0}
                     style={{ 
                        pointerEvents: 'none',
                        filter: `brightness(${activeVideoClip?.filters?.brightness ?? 1}) contrast(${activeVideoClip?.filters?.contrast ?? 1}) saturate(${activeVideoClip?.filters?.saturation ?? 1}) sepia(${activeVideoClip?.filters?.sepia ?? 0}%) blur(${activeVideoClip?.filters?.blur ?? 0}px) hue-rotate(${activeVideoClip?.filters?.hueRotate ?? 0}deg) invert(${activeVideoClip?.filters?.invert ?? 0}%) grayscale(${activeVideoClip?.filters?.grayscale ?? 0}%) ${activeVideoClip?.filters?.customCss ?? ''}`
                     }}
                  />
                  {activeAudioFile && (
                     <audio 
                        ref={audioRef}
                        src={activeAudioFile.url}
                        muted={activeAudioClip?.filters?.volume === 0}
                        onWaiting={() => setIsBuffering(true)}
                        onPlaying={() => setIsBuffering(false)}
                        onCanPlay={() => setIsBuffering(false)}
                     />
                  )}
               </div>
            ) : (
               <div className="text-neutral-700 flex flex-col items-center">
                  <span className="font-mono text-sm">No Video</span>
                  <span className="text-xs">at {currentTime.toFixed(2)}s</span>
                  {activeAudioFile && (
                     <audio 
                        ref={audioRef}
                        src={activeAudioFile.url}
                        muted={activeAudioClip?.filters?.volume === 0}
                        onWaiting={() => setIsBuffering(true)}
                        onPlaying={() => setIsBuffering(false)}
                        onCanPlay={() => setIsBuffering(false)}
                     />
                  )}
               </div>
            )}
            
            {/* Resolution indicator mock */}
            <div className="absolute top-2 left-2 bg-black/60 px-2 flex items-center h-5 rounded text-[10px] font-mono text-neutral-400">
               {globalSettings.quality}
            </div>
         </div>
      </div>
      
      {/* Controls */}
      <div className="h-12 bg-neutral-950 border-t border-neutral-900 flex items-center justify-between px-4 text-neutral-400">
         <div className="flex items-center gap-2 w-32 shrink-0">
            <span className="font-mono text-xs w-12 text-right">{currentTime.toFixed(2)}s</span>
            <span className="text-neutral-700">/</span>
            <span className="font-mono text-xs w-12">
              {Math.max(0, ...clips.map(c => c.startTime + c.duration)).toFixed(2)}s
            </span>
         </div>
         
         <div className="flex items-center gap-1 sm:gap-2">
            <button onClick={() => onTimeChange(0)} className="p-1.5 hover:text-white transition-colors" title="Go to Start">
               <SkipBack className="w-4 h-4"/>
            </button>
            <button onClick={() => onTimeChange(Math.max(0, currentTime - 1/30))} className="p-1.5 hover:text-white transition-colors" title="Step Backward 1 Frame">
               <StepBack className="w-4 h-4"/>
            </button>
            <button onClick={onTogglePlay} className="p-2 bg-white text-black rounded-full hover:bg-neutral-200 transition-transform hover:scale-105 mx-1 sm:mx-2">
               {isPlaying ? <Pause className="w-4 h-4 fill-current"/> : <Play className="w-4 h-4 fill-current"/>}
            </button>
            <button onClick={() => onTimeChange(currentTime + 1/30)} className="p-1.5 hover:text-white transition-colors" title="Step Forward 1 Frame">
               <StepForward className="w-4 h-4"/>
            </button>
            <button onClick={() => onTimeChange(Math.max(0, ...clips.map(c => c.startTime + c.duration)))} className="p-1.5 hover:text-white transition-colors" title="Go to End">
               <SkipForward className="w-4 h-4"/>
            </button>
         </div>

         <div className="flex items-center justify-end w-32 shrink-0">
            <select 
               value={playbackRate} 
               onChange={(e) => onPlaybackRateChange(Number(e.target.value))}
               className="bg-neutral-900 border border-neutral-800 rounded px-2 py-1 text-xs outline-none text-neutral-300"
            >
               <option value={0.25}>0.25x</option>
               <option value={0.5}>0.5x</option>
               <option value={1}>1.0x</option>
               <option value={1.5}>1.5x</option>
               <option value={2}>2.0x</option>
            </select>
         </div>
      </div>
    </div>
  );
}
