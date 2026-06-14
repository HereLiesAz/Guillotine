import React, { useState, useEffect } from 'react';
import { X, Download, HardDrive, Clock, AlertCircle, Loader2 } from 'lucide-react';
import { cn } from '../lib/utils';

interface ExportModalProps {
   isOpen: boolean;
   onClose: () => void;
   totalDuration: number;
}

const PRESETS = [
   { id: 'original', name: 'Original', recommendedFormat: 'mp4' },
   { id: '4k', name: '4K (2160p)', recommendedFormat: 'mp4' },
   { id: '1440p', name: 'Quad HD (1440p)', recommendedFormat: 'mp4' },
   { id: '1080p', name: 'Full HD (1080p)', recommendedFormat: 'mp4' },
   { id: '720p', name: 'HD (720p)', recommendedFormat: 'mp4' },
   { id: '480p', name: 'SD (480p)', recommendedFormat: 'mp4' },
   { id: 'audio_mp3', name: 'Audio Only (High Quality)', recommendedFormat: 'mp3' },
   { id: 'audio_wav', name: 'Audio Only (Lossless)', recommendedFormat: 'wav' }
];

const FORMATS = ['mp4', 'mkv', 'webm', 'mov', 'mp3', 'wav'];

export default function ExportModal({ isOpen, onClose, totalDuration }: ExportModalProps) {
   const [fileName, setFileName] = useState("My_Awesome_Video");
   const [preset, setPreset] = useState("1080p");
   const [format, setFormat] = useState("mp4");
   
   const [isRendering, setIsRendering] = useState(false);
   const [progress, setProgress] = useState(0);
   const [timeElapsed, setTimeElapsed] = useState(0);
   const [timeRemaining, setTimeRemaining] = useState(0);
   const [isCheckingDisk, setIsCheckingDisk] = useState(false);
   const [diskError, setDiskError] = useState("");
   const [renderComplete, setRenderComplete] = useState(false);

   // Estimate file size based on preset and duration
   const getEstimatedSizeMB = () => {
      let mbPerSecond = 1;
      if (preset === '4k') mbPerSecond = 5;
      else if (preset === '1440p') mbPerSecond = 3;
      else if (preset === '1080p' || preset === 'original') mbPerSecond = 1.5;
      else if (preset === '720p') mbPerSecond = 0.8;
      else if (preset === '480p') mbPerSecond = 0.3;
      else if (preset === 'audio_wav') mbPerSecond = 0.2;
      else if (preset === 'audio_mp3') mbPerSecond = 0.04;
      
      return (totalDuration * mbPerSecond).toFixed(1);
   };

   useEffect(() => {
      // Auto-update recommended format when preset changes
      const found = PRESETS.find(p => p.id === preset);
      if (found && (format === 'mp3' || format === 'wav') && !preset.startsWith('audio_')) {
          setFormat('mp4');
      } else if (preset === 'audio_mp3') {
          setFormat('mp3');
      } else if (preset === 'audio_wav') {
          setFormat('wav');
      }
   }, [preset]);

   const formatTime = (seconds: number) => {
      const m = Math.floor(seconds / 60);
      const s = Math.floor(seconds % 60);
      return `${m}m ${s}s`;
   };

   const startRender = async () => {
       setIsCheckingDisk(true);
       setDiskError("");
       
       // Simulate disk space check
       await new Promise(r => setTimeout(r, 1500));
       
       const estimatedSize = parseFloat(getEstimatedSizeMB());
       // Simulate an arbitrary disk limit check just for realism
       if (estimatedSize > 50000) { // 50GB arbitrary limit for demo
           setDiskError("Not enough disk space available. Please clear some space and try again.");
           setIsCheckingDisk(false);
           return;
       }

       setIsCheckingDisk(false);
       setIsRendering(true);
       setProgress(0);
       setTimeElapsed(0);
       setRenderComplete(false);

       const renderStartTime = Date.now();
       const renderDurationMs = totalDuration * 1000 * 0.5; // Simulate render taking half the time of the clip length (can be adjusted)

       const renderInterval = setInterval(() => {
           const now = Date.now();
           const elapsed = now - renderStartTime;
           const currentProgress = Math.min((elapsed / renderDurationMs) * 100, 100);
           
           setProgress(currentProgress);
           setTimeElapsed(Math.floor(elapsed / 1000));
           
           if (currentProgress > 0 && currentProgress < 100) {
               const estimatedTotalTime = (elapsed / currentProgress) * 100;
               setTimeRemaining(Math.max(0, Math.floor((estimatedTotalTime - elapsed) / 1000)));
           } else if (currentProgress >= 100) {
               setTimeRemaining(0);
           }

           if (currentProgress >= 100) {
               clearInterval(renderInterval);
               setRenderComplete(true);
           }
       }, 200);
   };

   const handleClose = () => {
       if (isRendering && !renderComplete) {
           if (!window.confirm("Are you sure you want to cancel the render?")) return;
       }
       // Reset state
       setIsRendering(false);
       setProgress(0);
       setTimeElapsed(0);
       setTimeRemaining(0);
       setRenderComplete(false);
       setDiskError("");
       onClose();
   };

   if (!isOpen) return null;

   return (
      <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100] flex items-center justify-center p-4">
         <div className="bg-neutral-900 border border-neutral-800 rounded-xl shadow-2xl w-full max-w-lg overflow-hidden flex flex-col">
            <div className="flex items-center justify-between p-4 border-b border-neutral-800">
               <h2 className="text-sm font-medium tracking-tight text-white flex items-center gap-2">
                  <Download className="w-4 h-4 text-red-500" />
                  Export Project
               </h2>
               <button onClick={handleClose} className="p-1 hover:bg-neutral-800 rounded text-neutral-400 max-w-2xl"><X className="w-4 h-4" /></button>
            </div>

            <div className="p-4 flex flex-col gap-4">
               {(!isRendering && !renderComplete) ? (
                  <>
                     <div className="space-y-1">
                        <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">File Name</label>
                        <input 
                              type="text" 
                              value={fileName} 
                              onChange={(e) => setFileName(e.target.value)}
                              className="w-full bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white"
                        />
                     </div>

                     <div className="grid grid-cols-2 gap-4">
                        <div className="space-y-1">
                           <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">Preset</label>
                           <select 
                                 value={preset} 
                                 onChange={(e) => setPreset(e.target.value)}
                                 className="w-full bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white"
                           >
                              {PRESETS.map(p => (
                                  <option key={p.id} value={p.id}>{p.name}</option>
                              ))}
                           </select>
                        </div>
                        <div className="space-y-1">
                           <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">Format</label>
                           <select 
                                 value={format} 
                                 onChange={(e) => setFormat(e.target.value)}
                                 className="w-full bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white"
                           >
                              {FORMATS.map(f => (
                                  <option key={f} value={f} disabled={
                                     (preset.startsWith('audio_') && (f !== 'mp3' && f !== 'wav')) ||
                                     (!preset.startsWith('audio_') && (f === 'mp3' || f === 'wav'))
                                  }>.{f.toUpperCase()}</option>
                              ))}
                           </select>
                        </div>
                     </div>

                     <div className="bg-black/50 border border-neutral-800 rounded-lg p-3 flex flex-col gap-2">
                        <h3 className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">Export Summary</h3>
                        <div className="flex justify-between items-center text-xs">
                           <span className="text-neutral-500">Timeline Duration:</span>
                           <span className="text-neutral-300 font-mono">{totalDuration.toFixed(2)}s</span>
                        </div>
                        <div className="flex justify-between items-center text-xs">
                           <span className="text-neutral-500">Estimated File Size:</span>
                           <span className="text-neutral-300 font-mono">~{getEstimatedSizeMB()} MB</span>
                        </div>
                        <div className="flex justify-between items-center text-xs">
                           <span className="text-neutral-500">Output File:</span>
                           <span className="text-neutral-300 font-mono truncate ml-4">{fileName}.{format}</span>
                        </div>
                     </div>

                     {diskError && (
                         <div className="text-xs text-red-400 bg-red-400/10 p-2 rounded flex items-center gap-2">
                            <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                            {diskError}
                         </div>
                     )}
                  </>
               ) : (
                  <div className="py-6 flex flex-col items-center justify-center gap-6">
                     {renderComplete ? (
                         <>
                            <div className="w-16 h-16 rounded-full bg-red-500/20 flex items-center justify-center">
                                <Download className="w-8 h-8 text-red-500" />
                            </div>
                            <div className="text-center">
                                <h3 className="text-lg font-medium tracking-tight text-white mb-1">Export Complete</h3>
                                <p className="text-xs text-neutral-400">Your file <span className="text-neutral-300 font-mono">{fileName}.{format}</span> has been saved to your downloads.</p>
                            </div>
                         </>
                     ) : (
                         <>
                            <div className="w-full space-y-2">
                                <div className="flex justify-between items-end text-xs mb-2">
                                    <span className="text-neutral-300 font-medium">Rendering Video...</span>
                                    <span className="font-mono text-red-500">{progress.toFixed(1)}%</span>
                                </div>
                                <div className="w-full bg-black rounded-full h-3 border border-neutral-800 overflow-hidden relative">
                                    <div 
                                        className="bg-red-500 h-full transition-all duration-200 ease-out" 
                                        style={{ width: `${progress}%` }}
                                    />
                                    <div className="absolute inset-0 bg-[linear-gradient(45deg,transparent_25%,rgba(255,255,255,0.2)_25%,rgba(255,255,255,0.2)_50%,transparent_50%,transparent_75%,rgba(255,255,255,0.2)_75%,rgba(255,255,255,0.2)_100%)] bg-[length:20px_20px] animate-[progress_1s_linear_infinite] opacity-50" />
                                </div>
                            </div>
                            
                            <div className="flex w-full justify-between pt-2 border-t border-neutral-800">
                                <div className="flex items-center gap-2 text-xs text-neutral-400">
                                   <Clock className="w-3.5 h-3.5" />
                                   <span>Elapsed: <span className="font-mono">{formatTime(timeElapsed)}</span></span>
                                </div>
                                <div className="flex items-center gap-2 text-xs text-neutral-400">
                                   <Clock className="w-3.5 h-3.5 opacity-50" />
                                   <span>Remaining: <span className="font-mono">{formatTime(timeRemaining)}</span></span>
                                </div>
                            </div>
                         </>
                     )}
                  </div>
               )}
            </div>

            <div className="p-4 border-t border-neutral-800 flex justify-end gap-2 bg-neutral-950">
               {!renderComplete ? (
                  <>
                     <button 
                        onClick={handleClose}
                        disabled={isCheckingDisk}
                        className="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors font-medium disabled:opacity-50"
                     >
                        Cancel
                     </button>
                     <button 
                        onClick={startRender}
                        disabled={isRendering || isCheckingDisk}
                        className="px-4 py-1.5 text-xs bg-red-600 hover:bg-red-500 text-white rounded font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2 min-w-[120px] justify-center"
                     >
                        {isCheckingDisk ? (
                            <><HardDrive className="w-3.5 h-3.5 animate-pulse"/> Checking Disk...</>
                        ) : isRendering ? (
                            <><Loader2 className="w-3.5 h-3.5 animate-spin"/> Rendering...</>
                        ) : (
                            <><Download className="w-3.5 h-3.5"/> Start Render</>
                        )}
                     </button>
                  </>
               ) : (
                  <button 
                     onClick={handleClose}
                     className="px-6 py-1.5 text-xs bg-neutral-800 hover:bg-neutral-700 text-white rounded font-medium transition-colors"
                  >
                     Close
                  </button>
               )}
            </div>
         </div>
      </div>
   );
}
