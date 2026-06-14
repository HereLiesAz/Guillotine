import React, { useState, useEffect } from 'react';
import { TimelineClip, MediaFile, GlobalSettings, TimelineKeyframe, ClipFilters } from '../types';
import PlayerCanvas from './PlayerCanvas';
import { TimelineNLE } from './TimelineNLE';
import Inspector from './Inspector';
import { Scissors, Save, FolderOpen, Image as ImageIcon, Download, Settings, Undo, Redo, Menu, Upload } from 'lucide-react';
import GooglePhotosSelector from './GooglePhotosSelector';
import AIGeneratorModal from './AIGeneratorModal';
import ExportModal from './ExportModal';
import { useUndoRedo } from '../hooks/useUndoRedo';
import SettingsModal from './SettingsModal';

export default function NLEWorkspace() {
  const [mediaFiles, setMediaFiles] = useState<MediaFile[]>([]);
  const [clips, setClips, undo, redo, canUndo, canRedo] = useUndoRedo<TimelineClip[]>([]);
  const [videoTracks, setVideoTracks] = useState<string[]>(['V1']);
  const [audioTracks, setAudioTracks] = useState<string[]>(['A1']);
  const [currentTime, setCurrentTime] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [zoom, setZoom] = useState(10);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [selectedClipIds, setSelectedClipIds] = useState<string[]>([]);
  const selectedClipId = selectedClipIds.length === 1 ? selectedClipIds[0] : null;
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isAIGeneratorOpen, setIsAIGeneratorOpen] = useState(false);
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const menuRef = React.useRef<HTMLDivElement>(null);
  const clipboardClip = React.useRef<TimelineClip | null>(null);
  const clipboardFilters = React.useRef<ClipFilters | null>(null);

  React.useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);
  
  const [activeTool, setActiveTool] = useState<'select' | 'split' | 'keyframe'>('select');

  const handleDeleteSelected = () => {
    if (selectedClipIds.length > 0) {
       setClips(clips.filter(c => !selectedClipIds.includes(c.id)));
       setSelectedClipIds([]);
    }
  };

  const handleSaveProject = () => {
    const projectData = {
       mediaFiles: mediaFiles.map(m => ({ ...m, file: undefined })),
       clips,
       globalSettings,
       currentTime,
       zoom
    };
    
    const blob = new Blob([JSON.stringify(projectData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `project.gilt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleLoadProject = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    
    const reader = new FileReader();
    reader.onload = (event) => {
       try {
          const contents = event.target?.result as string;
          const projectData = JSON.parse(contents);
          
          if (projectData.mediaFiles) setMediaFiles(projectData.mediaFiles);
          if (projectData.clips) setClips(projectData.clips);
          if (projectData.globalSettings) setGlobalSettings(projectData.globalSettings);
          if (projectData.currentTime !== undefined) setCurrentTime(projectData.currentTime);
          if (projectData.zoom !== undefined) setZoom(projectData.zoom);
       } catch (err) {
          console.error("Failed to load project", err);
       }
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const handleSplitClip = (clipId: string, timeInClip: number) => {
    const clipIndex = clips.findIndex(c => c.id === clipId);
    if (clipIndex === -1) return;
    const clip = clips[clipIndex];
    if (timeInClip <= 0 || timeInClip >= clip.duration) return;

    const newClips = [...clips];
    
    const clip2: TimelineClip = {
       ...clip,
       id: Math.random().toString(),
       startTime: clip.startTime + timeInClip,
       trimStart: clip.trimStart + timeInClip,
       duration: clip.duration - timeInClip,
       keyframes: clip.keyframes.map(k => ({...k}))
    };

    newClips[clipIndex] = {
       ...clip,
       duration: timeInClip,
       keyframes: clip.keyframes.map(k => ({...k}))
    };

    newClips.push(clip2);
    setClips(newClips);
  };

  const [globalSettings, setGlobalSettings] = useState<GlobalSettings>({
    aspectRatio: "Original",
    quality: "Original",
    crop: { x: 0, y: 0, w: 100, h: 100 }
  });

  const [isPhotoSelectorOpen, setIsPhotoSelectorOpen] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Playback loop
  useEffect(() => {
     if (!isPlaying) return;
     let lastTime = performance.now();
     let animationFrame: number;
     const tick = (time: number) => {
        const delta = ((time - lastTime) / 1000) * playbackRate;
        lastTime = time;
        setCurrentTime(t => t + delta);
        animationFrame = requestAnimationFrame(tick);
     };
     animationFrame = requestAnimationFrame(tick);
     return () => cancelAnimationFrame(animationFrame);
  }, [isPlaying, playbackRate]);

  useEffect(() => {
     const handleKeyDown = (e: KeyboardEvent) => {
        // Ignore if typing in an input
        if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

        if (e.code === 'Space') {
           e.preventDefault();
           setIsPlaying(p => !p);
        } else if (e.code === 'Backspace' || e.code === 'Delete') {
           if (selectedClipIds.length > 0) {
              setClips(clips.filter(c => !selectedClipIds.includes(c.id)));
              setSelectedClipIds([]);
           }
        } else if (e.key.toLowerCase() === 'z' && (e.ctrlKey || e.metaKey)) {
           if (e.shiftKey) {
              if (canRedo) redo();
           } else {
              if (canUndo) undo();
           }
        } else if (e.key.toLowerCase() === 'c' && (e.ctrlKey || e.metaKey)) {
           if (e.altKey) {
               // Copy filters
               if (selectedClipIds.length === 1) {
                  const clip = clips.find(c => c.id === selectedClipIds[0]);
                  if (clip) clipboardFilters.current = JSON.parse(JSON.stringify(clip.filters));
               }
           } else {
               // Copy clip
               if (selectedClipIds.length === 1) {
                  const clip = clips.find(c => c.id === selectedClipIds[0]);
                  if (clip) clipboardClip.current = JSON.parse(JSON.stringify(clip));
               }
           }
        } else if (e.key.toLowerCase() === 'v' && (e.ctrlKey || e.metaKey)) {
           if (e.altKey) {
               // Paste filters
               if (selectedClipIds.length > 0 && clipboardFilters.current) {
                  const newFilters = JSON.parse(JSON.stringify(clipboardFilters.current));
                  setClips(clips.map(c => selectedClipIds.includes(c.id) ? { ...c, filters: { ...newFilters } } : c));
               }
           } else {
               // Paste clip
               if (clipboardClip.current) {
                  const pastedClip: TimelineClip = {
                     ...JSON.parse(JSON.stringify(clipboardClip.current)),
                     id: Math.random().toString(),
                     startTime: currentTime,
                  };
                  setClips([...clips, pastedClip]);
               }
           }
        } else if (e.key.toLowerCase() === 'y' && (e.ctrlKey || e.metaKey)) {
           if (canRedo) redo();
        } else if (e.key.toLowerCase() === 's') {
           if (selectedClipIds.length === 1) {
              handleSplitClip(selectedClipIds[0], currentTime - (clips.find(c => c.id === selectedClipIds[0])?.startTime || 0));
           }
        }
     };

     window.addEventListener('keydown', handleKeyDown);
     return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isPlaying, selectedClipIds, clips, canUndo, canRedo, currentTime]);

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files) {
      const newFiles = Array.from(e.target.files).map((file: any) => {
        const id = Math.random().toString();
        const url = URL.createObjectURL(file as Blob);
        const name = file.name;
        const isRemote = false;
        
        return new Promise<MediaFile>((resolve) => {
           if (file.type.startsWith('image/')) {
              const img = new Image();
              img.src = url;
              img.onload = () => {
                 resolve({ id, file, name, isRemote, url, duration: 5 }); // 5s default duration for images
              };
           } else {
              const video = document.createElement("video");
              video.src = url;
              video.onloadedmetadata = () => {
                 resolve({ id, file, name, isRemote, url, duration: video.duration });
              };
           }
        });
      });

      Promise.all(newFiles).then(res => {
         setMediaFiles(prev => [...prev, ...res]);
         
         // Auto-add to timeline
         let t = Math.max(0, ...clips.map(c => c.startTime + c.duration));
         const newClips: TimelineClip[] = [];
         
         res.forEach(m => {
            newClips.push({
               id: Math.random().toString(),
               mediaId: m.id,
               type: 'video',
               trackId: 'V1',
               startTime: t,
               trimStart: 0,
               duration: m.duration,
               prompt: '',
               edits: [],
               keyframes: [],
               filters: { brightness: 1, contrast: 1, saturation: 1 },
               isAnalyzing: false
            });
            newClips.push({
               id: Math.random().toString(),
               mediaId: m.id,
               type: 'audio',
               trackId: 'A1',
               startTime: t,
               trimStart: 0,
               duration: m.duration,
               prompt: '',
               edits: [],
               keyframes: [],
               filters: { brightness: 1, contrast: 1, saturation: 1 },
               isAnalyzing: false
            });
            t += m.duration;
         });
         
         setClips(prev => [...prev, ...newClips]);
      });
    }
  };

  const handleAddKeyframe = (clipId: string, property: TimelineKeyframe['property']) => {
    setClips(clips.map(c => {
       if (c.id !== clipId) return c;
       
       // Just add a keyframe at the current playhead if it's within clip,
       // otherwise add at start
       let time = 0;
       if (currentTime >= c.startTime && currentTime <= c.startTime + c.duration) {
          time = currentTime - c.startTime;
       }

       const newKf: TimelineKeyframe = {
          id: Math.random().toString(),
          time,
          property,
          value: property === 'scale' || property === 'opacity' ? 1 : 0.5,
          easing: [0.25, 0.1, 0.25, 1] // default ease
       };

       return {
          ...c,
          keyframes: [...c.keyframes, newKf].sort((a,b) => a.time - b.time)
       };
    }));
  };

  const handleUpdateKeyframe = (clipId: string, keyframeId: string, updates: Partial<TimelineKeyframe>) => {
    setClips(clips.map(c => {
       if (c.id !== clipId) return c;
       return {
          ...c,
          keyframes: c.keyframes.map(k => k.id === keyframeId ? { ...k, ...updates } : k)
       };
    }));
  };

  const handleRemoveKeyframe = (clipId: string, keyframeId: string) => {
    setClips(clips.map(c => {
       if (c.id !== clipId) return c;
       return {
          ...c,
          keyframes: c.keyframes.filter(k => k.id !== keyframeId)
       };
    }));
  };

  const selectedClip = clips.find(c => c.id === selectedClipId) || null;

  const updateClip = (id: string, updates: Partial<TimelineClip>) => {
     setClips(clips.map(c => c.id === id ? { ...c, ...updates } : c));
  };

  const handleAnalyzeClip = async () => {
     if (selectedClipIds.length === 0) return;
     setIsProcessing(true);
     setError(null);
     
     const runAnalyze = async (clipId: string) => {
        const clip = clips.find(c => c.id === clipId);
        if (!clip || !clip.prompt) return;
        const media = mediaFiles.find(m => m.id === clip.mediaId);
        if (!media) throw new Error("Media not found");

        const formData = new FormData();
        formData.append("prompt", clip.prompt);
        formData.append("type", clip.type);
        
        const aiProvider = localStorage.getItem('aiProvider') || 'gemini';
        const aiKey = localStorage.getItem('aiKey') || '';
        
        formData.append("aiProvider", aiProvider);
        formData.append("aiKey", aiKey);

        if (media.isRemote) {
           formData.append("remoteUrl", media.url);
           formData.append("remoteName", media.name);
        } else if (media.file) {
           formData.append("media", media.file);
        }

        const res = await fetch("/api/analyze-clip", { method: "POST", body: formData });
        if (!res.ok) {
           const err = await res.json();
           throw new Error(err.error || "Failed to analyze clip");
        }
        return await res.json();
     };

     try {
        const promises = selectedClipIds.map(id => runAnalyze(id).then(data => ({ id, data })));
        const results = await Promise.all(promises);
        
        setClips(prev => prev.map(c => {
           const result = results.find(r => r.id === c.id);
           if (result && result.data?.edits) {
              return { ...c, edits: result.data.edits };
           }
           return c;
        }));
     } catch (e: any) {
        setError(e.message);
     } finally {
        setIsProcessing(false);
     }
  };

  return (
    <div className="h-screen bg-black text-white flex flex-col font-sans overflow-hidden">
      {/* Top Bar */}
      <header className="h-10 border-b border-neutral-800 bg-neutral-950 flex items-center justify-between px-2 shrink-0">
         <div className="flex items-center gap-2">
            <svg 
               xmlns="http://www.w3.org/2000/svg" 
               viewBox="0 0 24 24" 
               fill="none" 
               stroke="currentColor" 
               className="w-5 h-5 text-red-500"
               strokeWidth="2" 
               strokeLinecap="round" 
               strokeLinejoin="round"
            >
               <path d="M7 3h10v18H7z"/>
               <path d="M5 21h14"/>
               <path d="M9 3v18"/>
               <path d="M15 3v18"/>
               <path d="M7 8l10 4v3l-10 4z"/>
            </svg>
            <h1 className="font-medium tracking-tight text-sm">Guillotine</h1>
         </div>
         <div className="flex gap-2" ref={menuRef}>
            <div className="relative">
               <button 
                  onClick={() => setIsMenuOpen(!isMenuOpen)} 
                  className="flex items-center gap-2 px-3 py-1.5 bg-neutral-900 border border-neutral-800 hover:bg-neutral-800 rounded-md text-xs transition-colors text-neutral-300 pointer-events-auto"
               >
                  <Menu className="w-4 h-4" />
                  <span className="hidden leading-none sm:inline">Menu</span>
               </button>
               
               {isMenuOpen && (
               <div className="absolute top-full right-0 mt-2 w-56 bg-neutral-900 border border-neutral-800 rounded-lg shadow-2xl z-50 flex flex-col py-2 transition-opacity">
                  <div className="px-3 pb-2 pt-1 text-[10px] uppercase font-semibold text-neutral-500 tracking-wider">Project</div>
                  <button onClick={() => { setIsMenuOpen(false); handleSaveProject(); }} className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs text-neutral-300 transition-colors w-full">
                     <Save className="w-4 h-4"/> Save Project
                  </button>
                  <label className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs cursor-pointer text-neutral-300 transition-colors w-full">
                     <FolderOpen className="w-4 h-4"/> Load Project (.gilt)
                     <input type="file" onChange={(e) => { setIsMenuOpen(false); handleLoadProject(e); }} accept=".gilt" className="hidden" />
                  </label>

                  <div className="h-px bg-neutral-800 my-1"></div>
                  <div className="px-3 py-2 pt-3 text-[10px] uppercase font-semibold text-neutral-500 tracking-wider">Media</div>
                  <label className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs cursor-pointer text-neutral-300 transition-colors w-full">
                     <Upload className="w-4 h-4"/> Upload Local Files
                     <input type="file" onChange={(e) => { setIsMenuOpen(false); handleFileUpload(e); }} accept="image/*,video/*,audio/*" className="hidden" multiple />
                  </label>
                  <button onClick={() => { setIsMenuOpen(false); setIsPhotoSelectorOpen(true); }} className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs text-neutral-300 transition-colors w-full">
                     <ImageIcon className="w-4 h-4"/> Import from Photos
                  </button>

                  <div className="h-px bg-neutral-800 my-1"></div>
                  <button onClick={() => { setIsMenuOpen(false); setIsExportModalOpen(true); }} className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs text-neutral-300 transition-colors w-full">
                     <Download className="w-4 h-4"/> Export Project
                  </button>

                  <div className="h-px bg-neutral-800 my-1"></div>
                  <button onClick={() => { setIsMenuOpen(false); setIsSettingsOpen(true); }} className="flex items-center gap-3 px-3 py-2 hover:bg-neutral-800 text-left text-xs text-neutral-300 transition-colors w-full">
                     <Settings className="w-4 h-4" /> Workspace Settings
                  </button>
               </div>
               )}
            </div>
         </div>
      </header>

      {/* Main Workspace Area */}
      <main className="flex-1 flex flex-col bg-neutral-950 overflow-hidden relative">
         {/* Top Half: Media/FX and Preview */}
         <div className="flex-[60%] flex flex-col md:flex-row overflow-hidden border-b border-neutral-800 relative">
            {/* Left: Inspector / Project Media */}
            <div className="flex-1 md:w-[45%] md:flex-none md:max-w-md border-r border-[#1a1a1a] min-h-[30%] shrink-0 flex overflow-hidden bg-[#242424]">
               <Inspector 
                  selectedClips={clips.filter(c => selectedClipIds.includes(c.id))}
                  globalSettings={globalSettings}
                  setGlobalSettings={setGlobalSettings}
                  updateClip={updateClip}
                  onAnalyzeClip={handleAnalyzeClip}
                  isProcessing={isProcessing}
                  error={error}
                  onAddKeyframe={handleAddKeyframe}
                  onUpdateKeyframe={handleUpdateKeyframe}
                  onRemoveKeyframe={handleRemoveKeyframe}
               />
            </div>

            {/* Right: Preview */}
            <div className="flex-[1.5] relative min-h-0 flex overflow-hidden bg-black">
               <PlayerCanvas 
                  mediaFiles={mediaFiles} 
                  clips={clips} 
                  currentTime={currentTime} 
                  isPlaying={isPlaying} 
                  onTogglePlay={() => setIsPlaying(!isPlaying)}
                  globalSettings={globalSettings}
                  onTimeChange={setCurrentTime}
                  playbackRate={playbackRate}
                  onPlaybackRateChange={setPlaybackRate}
               />
            </div>
         </div>

         {/* Bottom: Timeline */}
         <div className="flex-[40%] flex flex-col shrink-0 min-h-[250px] bg-[#1e1e1e]">
            <TimelineNLE 
               mediaFiles={mediaFiles}
               clips={clips}
               currentTime={currentTime}
               onTimeChange={setCurrentTime}
               zoom={zoom}
               onZoomChange={setZoom}
               selectedClipId={selectedClipId}
               onSelectClip={(id) => setSelectedClipIds(prev => {
                   if (prev.includes(id)) return prev.filter(c => c !== id);
                   return [id];
               })}
               activeTool={activeTool}
               onToolChange={setActiveTool}
               onDeleteSelected={handleDeleteSelected}
               onSplitClip={handleSplitClip}
               videoTracks={videoTracks}
               audioTracks={audioTracks}
               onAddTrack={(type: "video" | "audio") => {
                  if (type === 'video') {
                     setVideoTracks(prev => [...prev, `V${prev.length + 1}`]);
                  } else {
                     setAudioTracks(prev => [...prev, `A${prev.length + 1}`]);
                  }
               }}
               onChangeClipTrack={(clipId, trackId, newStartTime) => {
                  setClips(clips.map(c => c.id === clipId ? { ...c, trackId, startTime: newStartTime ?? c.startTime } : c));
               }}
               onOpenAIGenerator={() => setIsAIGeneratorOpen(true)}
            />
         </div>
      </main>

      {isPhotoSelectorOpen && (
         <GooglePhotosSelector 
            onSelect={(items) => {
               setIsPhotoSelectorOpen(false);
               const newItems = items.map((photo) => {
                 const id = Math.random().toString();
                 const url = `${photo.baseUrl}=dv`;
                 
                 return new Promise<MediaFile>((resolve) => {
                    const video = document.createElement("video");
                    video.src = url;
                    video.onloadedmetadata = () => {
                       resolve({
                          id, name: photo.filename, isRemote: true, url, duration: video.duration
                       });
                    };
                 });
               });

               Promise.all(newItems).then(res => {
                  setMediaFiles(prev => [...prev, ...res]);
                  
                  let t = Math.max(0, ...clips.map(c => c.startTime + c.duration));
                  const newClips: TimelineClip[] = [];
                  
                  res.forEach(m => {
                     newClips.push({
                        id: Math.random().toString(),
                        mediaId: m.id,
                        type: 'video',
                        trackId: 'V1',
                        startTime: t,
                        trimStart: 0,
                        duration: m.duration,
                        prompt: '',
                        edits: [],
                        keyframes: [],
                        filters: { brightness: 1, contrast: 1, saturation: 1 },
                        isAnalyzing: false
                     });
                     newClips.push({
                        id: Math.random().toString(),
                        mediaId: m.id,
                        type: 'audio',
                        trackId: 'A1',
                        startTime: t,
                        trimStart: 0,
                        duration: m.duration,
                        prompt: '',
                        edits: [],
                        keyframes: [],
                        filters: { brightness: 1, contrast: 1, saturation: 1 },
                        isAnalyzing: false
                     });
                     t += m.duration;
                  });
                  setClips(prev => [...prev, ...newClips]);
               });
            }}
            onClose={() => setIsPhotoSelectorOpen(false)}
         />
      )}

      {isSettingsOpen && (
         <SettingsModal onClose={() => setIsSettingsOpen(false)} />
      )}

      <AIGeneratorModal
         isOpen={isAIGeneratorOpen}
         onClose={() => setIsAIGeneratorOpen(false)}
         selectedClip={selectedClipIds.length === 1 ? clips.find(c => c.id === selectedClipIds[0]) : null}
         onEdit={(clipId, url, name) => {
             const id = Math.random().toString();
             const newMedia: MediaFile = {
                 id,
                 url,
                 name,
                 duration: 5,
                 isRemote: true
             };
             setMediaFiles(prev => [...prev, newMedia]);
             setClips(clips.map(c => c.id === clipId ? { ...c, mediaId: id } : c));
         }}
         onGenerate={(url, name) => {
            const id = Math.random().toString();
            // Free generation / Mock or pollinations API
            const newMedia: MediaFile = {
               id,
               url,
               name,
               duration: 5,
               isRemote: true
            };
            setMediaFiles(prev => [...prev, newMedia]);
            
            setVideoTracks(prev => prev.includes('V1') ? prev : ['V1']);
            setClips([...clips, {
               id: Math.random().toString(),
               mediaId: id,
               type: 'video',
               trackId: 'V1',
               startTime: currentTime,
               trimStart: 0,
               duration: 5,
               prompt: '',
               edits: [],
               keyframes: [],
               filters: { brightness: 1, contrast: 1, saturation: 1 },
               isAnalyzing: false
            }]);
         }}
      />

      <ExportModal 
         isOpen={isExportModalOpen}
         onClose={() => setIsExportModalOpen(false)}
         totalDuration={Math.max(0, ...clips.map(c => c.startTime + c.duration))}
      />
    </div>
  );
}
