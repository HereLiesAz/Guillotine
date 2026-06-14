import React, { useState, useRef, useEffect } from 'react';
import { TimelineClip, MediaFile, GlobalSettings } from '../types';
import { Play, Pause, ZoomIn, ZoomOut, Save, Plus, Scissors, Trash, MousePointer, Diamond, Sparkles } from 'lucide-react';
import TimelineTrack from './TimelineTrack';
import PlayerCanvas from './PlayerCanvas';
import { cn } from '../lib/utils';

interface TimelineProps {
  mediaFiles: MediaFile[];
  clips: TimelineClip[];
  currentTime: number;
  onTimeChange: (time: number) => void;
  zoom: number;
  onZoomChange: (zoom: number) => void;
  selectedClipId: string | null;
  onSelectClip: (id: string | null) => void;
  activeTool: 'select' | 'split' | 'keyframe';
  onToolChange: (tool: 'select' | 'split' | 'keyframe') => void;
  onDeleteSelected: () => void;
  onSplitClip: (clipId: string, time: number) => void;
  videoTracks: string[];
  audioTracks: string[];
  onAddTrack: (type: 'video' | 'audio') => void;
  onChangeClipTrack: (clipId: string, trackId: string, newStartTime?: number) => void;
  onOpenAIGenerator?: () => void;
}

export function TimelineNLE({
  mediaFiles,
  clips,
  currentTime,
  onTimeChange,
  zoom,
  onZoomChange,
  selectedClipId,
  onSelectClip,
  activeTool,
  onToolChange,
  onDeleteSelected,
  onSplitClip,
  videoTracks,
  audioTracks,
  onAddTrack,
  onChangeClipTrack,
  onOpenAIGenerator
}: TimelineProps) {
  
  const handleScroll = (e: React.WheelEvent) => {
    if (e.ctrlKey || e.metaKey) {
       e.preventDefault();
       onZoomChange(Math.max(1, Math.min(50, zoom + (e.deltaY > 0 ? -1 : 1))));
    }
  };

  const videoClips = clips.filter(c => c.type === 'video');
  const audioClips = clips.filter(c => c.type === 'audio');
  
  const handleTrackInteraction = (e: React.MouseEvent, clip: TimelineClip) => {
     if (activeTool === 'select') {
        onSelectClip(clip.id);
     } else if (activeTool === 'split') {
        const rect = e.currentTarget.getBoundingClientRect();
        const mouseX = e.clientX - rect.left;
        const timeInClip = mouseX / (zoom * 10);
        
        // Time is relative to clip start (trimStart represents offset within original file)
        onSplitClip(clip.id, timeInClip);
     } else if (activeTool === 'keyframe') {
        onSelectClip(clip.id);
        // Note: Keyframe tool adds keyframes usually on Inspector or straight on timeline
        // Let's do simple select to show keyframe curve editor
     }
  };

  return (
    <div className="flex flex-col bg-neutral-900 border-t border-neutral-800 h-full shrink-0 relative" onWheel={handleScroll}>
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-neutral-800 bg-black/20 shrink-0">
         <div className="flex items-center gap-4 text-xs text-neutral-400">
            <span>Video Tracks: {videoTracks.length}</span>
            <span>Audio Tracks: {audioTracks.length}</span>
         </div>
         <div className="flex items-center gap-2">
            <button className="px-2 py-1 hover:bg-neutral-800 rounded text-red-500 transition-colors text-[10px] font-bold tracking-wider" onClick={onOpenAIGenerator} title="AI Assistant">AI</button>
            <button className="p-1.5 hover:bg-neutral-800 rounded text-neutral-400 transition-colors" onClick={() => {
               const activeType = clips.find(c => c.id === selectedClipId)?.type;
               if (activeType === 'audio') onAddTrack('audio');
               else onAddTrack('video'); // default or if video is selected
            }} title="Add Track"><Plus className="w-4 h-4"/></button>
            <div className="w-px h-4 bg-neutral-700 mx-1 hidden sm:block" />
            <button className={cn("p-1.5 rounded transition-colors", activeTool === 'select' ? "bg-red-500/20 text-red-400" : "hover:bg-neutral-800 text-neutral-400")} onClick={() => onToolChange('select')}><MousePointer className="w-4 h-4"/></button>
            <button className={cn("p-1.5 rounded transition-colors", activeTool === 'split' ? "bg-red-500/20 text-red-400" : "hover:bg-neutral-800 text-neutral-400")} onClick={() => onToolChange('split')}><Scissors className="w-4 h-4"/></button>
            <button className="p-1.5 hover:bg-neutral-800 rounded text-neutral-400 transition-colors" onClick={onDeleteSelected} disabled={!selectedClipId}><Trash className="w-4 h-4"/></button>
            <div className="w-px h-4 bg-neutral-700 mx-2 hidden sm:block" />
            <button className={cn("p-1.5 rounded transition-colors", activeTool === 'keyframe' ? "bg-red-500/20 text-red-400" : "hover:bg-neutral-800 text-neutral-400")} onClick={() => onToolChange('keyframe')}><Diamond className="w-4 h-4"/></button>
         </div>
         <div className="flex items-center gap-2 hidden sm:flex">
            <button onClick={() => onZoomChange(Math.max(1, zoom - 2))} className="p-1 hover:bg-neutral-800 rounded text-neutral-400"><ZoomOut className="w-4 h-4"/></button>
            <input type="range" min="1" max="50" value={zoom} onChange={(e)=>onZoomChange(Number(e.target.value))} className="w-24 accent-neutral-500" />
            <button onClick={() => onZoomChange(Math.min(50, zoom + 2))} className="p-1 hover:bg-neutral-800 rounded text-neutral-400"><ZoomIn className="w-4 h-4"/></button>
         </div>
      </div>

      {/* Tracks Area */}
      <div className="flex-1 overflow-auto custom-scrollbar relative flex flex-col">
         <div className="absolute top-0 bottom-0 left-20 border-l border-red-500 z-50 pointer-events-none" style={{ transform: `translateX(${currentTime * zoom * 10}px)`}}>
            <div className="w-3 h-3 bg-red-500 rounded-full -translate-x-1.5 -translate-y-1.5 shadow-[0_0_10px_rgba(239,68,68,0.5)]" />
         </div>

         {/* Video Tracks */}
         {videoTracks.map((trackId, i) => (
            <div key={trackId} className="flex border-b border-neutral-800 flex-1 min-h-[4rem]"
                 onDragOver={e => e.preventDefault()}
                 onDrop={e => {
                    const clipId = e.dataTransfer.getData('clipId');
                    if (clipId) {
                       const rect = e.currentTarget.getBoundingClientRect();
                       const x = e.clientX - rect.left - 80; // 80 is the width of track header
                       const newTime = Math.max(0, x / (zoom * 10));
                       onChangeClipTrack(clipId, trackId, newTime); // track switch & move
                    }
                 }}>
               <div className="w-20 shrink-0 bg-neutral-900 border-r border-neutral-800 flex items-center justify-center text-xs font-mono text-neutral-500 sticky left-0 z-40">
                  {trackId}
               </div>
               <div 
                  className="flex-1 relative bg-neutral-950/50 cursor-text"
                  onClick={(e) => {
                     if (e.target === e.currentTarget) onSelectClip(null);
                     const rect = e.currentTarget.getBoundingClientRect();
                     const x = e.clientX - rect.left;
                     onTimeChange(Math.max(0, x / (zoom * 10)));
                  }}
               >
                  {videoClips.filter(c => c.trackId === trackId).map(clip => (
                     <TimelineTrack key={clip.id} clip={clip} zoom={zoom} isSelected={selectedClipId === clip.id} onInteraction={(e) => handleTrackInteraction(e, clip)} />
                  ))}
               </div>
            </div>
         ))}

         {/* Audio Tracks */}
         {audioTracks.map((trackId, i) => (
            <div key={trackId} className="flex border-b border-neutral-800 flex-1 min-h-[4rem]"
                 onDragOver={e => e.preventDefault()}
                 onDrop={e => {
                    const clipId = e.dataTransfer.getData('clipId');
                    if (clipId) {
                       const rect = e.currentTarget.getBoundingClientRect();
                       const x = e.clientX - rect.left - 80;
                       const newTime = Math.max(0, x / (zoom * 10));
                       onChangeClipTrack(clipId, trackId, newTime); // track switch & move
                    }
                 }}>
               <div className="w-20 shrink-0 bg-neutral-900 border-r border-neutral-800 flex items-center justify-center text-xs font-mono text-neutral-500 sticky left-0 z-40">
                  {trackId}
               </div>
               <div 
                  className="flex-1 relative bg-neutral-950/50 cursor-text"
                  onClick={(e) => {
                     if (e.target === e.currentTarget) onSelectClip(null);
                     const rect = e.currentTarget.getBoundingClientRect();
                     const x = e.clientX - rect.left;
                     onTimeChange(Math.max(0, x / (zoom * 10)));
                  }}
               >
                  {audioClips.filter(c => c.trackId === trackId).map(clip => (
                     <TimelineTrack key={clip.id} clip={clip} zoom={zoom} isSelected={selectedClipId === clip.id} onInteraction={(e) => handleTrackInteraction(e, clip)} />
                  ))}
               </div>
            </div>
         ))}
         
         <div style={{ width: `${Math.max(100, zoom * 100)}%` }} className="h-4 shrink-0" /> {/* Scroll padding */}
      </div>
    </div>
  );
}
