import React from 'react';
import { TimelineClip } from '../types';
import { cn } from '../lib/utils';
import { Film, Mic } from 'lucide-react';

interface TimelineTrackProps {
  key?: React.Key;
  clip: TimelineClip;
  zoom: number;
  isSelected: boolean;
  onInteraction: (e: React.MouseEvent) => void;
}

export default function TimelineTrack({ clip, zoom, isSelected, onInteraction }: TimelineTrackProps) {
  const PixelsPerSecond = 10 * zoom;
  const left = clip.startTime * PixelsPerSecond;
  
  // Actually duration might be affected by edits if implemented. For now, show original duration.
  // Wait, if edits exist, we draw the "keeps" and "removes" as overlays.
  const width = clip.duration * PixelsPerSecond;

  return (
    <div 
      draggable
      onDragStart={(e) => {
         e.dataTransfer.setData('clipId', clip.id);
      }}
      onClick={(e) => { e.stopPropagation(); onInteraction(e); }}
      className={cn(
        "absolute top-2 bottom-2 rounded cursor-pointer overflow-hidden group border select-none transition-colors",
        isSelected ? "bg-red-500/20 border-red-500/80 z-20"
                   : "bg-neutral-800 border-neutral-700 hover:border-neutral-500 z-10"
      )}
      style={{
        left: `${left}px`,
        width: `${width}px`
      }}
    >
      <div className="flex flex-col h-full opacity-50 group-hover:opacity-100 transition-opacity">
         <div className="flex items-center gap-1 p-1 bg-black/40 text-[10px] font-mono truncate">
            {clip.type === 'video' ? <Film className="w-3 h-3 text-red-500" /> : <Mic className="w-3 h-3 text-red-500" />}
            {clip.id.slice(0, 4)}
         </div>
         {/* Edit overlays */}
         {clip.edits.length > 0 && (
            <div className="absolute inset-0 top-5 pointer-events-none">
               {clip.edits.map((edit, idx) => {
                  const eLeft = (edit.start / clip.duration) * 100;
                  const eWidth = ((edit.end - edit.start) / clip.duration) * 100;
                  return (
                     <div key={idx} 
                        className="absolute top-0 bottom-0 opacity-40 mix-blend-screen"
                        style={{
                           left: `${eLeft}%`,
                           width: `${eWidth}%`,
                           backgroundColor: edit.action === 'keep' ? '#737373' : '#ef4444'
                        }}
                        title={edit.reason}
                     />
                  )
               })}
            </div>
         )}
         {/* Keyframe markers */}
         {clip.keyframes && clip.keyframes.length > 0 && (
            <div className="absolute bottom-1 left-0 right-0 h-2 pointer-events-none z-30">
               {clip.keyframes.map(kf => {
                  const kLeft = (kf.time / clip.duration) * 100;
                  return (
                     <div key={kf.id}
                        className="absolute top-0 w-2 h-2 bg-white rotate-45 transform -translate-x-1 shadow-sm"
                        style={{ left: `${kLeft}%` }}
                        title={`${kf.property}: ${kf.value}`}
                     />
                  )
               })}
            </div>
         )}
      </div>
    </div>
  );
}
