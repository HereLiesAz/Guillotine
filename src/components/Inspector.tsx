import React, { useState } from 'react';
import { TimelineClip, GlobalSettings, TimelineKeyframe } from '../types';
import { Sparkles, Settings2, Crop, Film, Mic, Diamond, Layers } from 'lucide-react';
import { cn } from '../lib/utils';
import CurveEditor from './CurveEditor';

interface InspectorProps {
  selectedClips: TimelineClip[];
  globalSettings: GlobalSettings;
  setGlobalSettings: (s: GlobalSettings) => void;
  updateClip: (id: string, updates: Partial<TimelineClip>) => void;
  onAnalyzeClip: () => void;
  isProcessing: boolean;
  error?: string | null;
  onAddKeyframe?: (clipId: string, property: TimelineKeyframe['property']) => void;
  onUpdateKeyframe?: (clipId: string, keyframeId: string, updates: Partial<TimelineKeyframe>) => void;
  onRemoveKeyframe?: (clipId: string, keyframeId: string) => void;
}

export default function Inspector({
  selectedClips,
  globalSettings,
  setGlobalSettings,
  updateClip,
  onAnalyzeClip,
  isProcessing,
  error,
  onAddKeyframe, // Need to handle these in NLEWorkspace
  onUpdateKeyframe,
  onRemoveKeyframe
}: InspectorProps) {

  const [activeProperty, setActiveProperty] = useState<TimelineKeyframe['property']>('opacity');
  const selectedClip = selectedClips.length === 1 ? selectedClips[0] : null;

  return (
    <div className="flex flex-col h-full bg-neutral-900 w-full overflow-y-auto custom-scrollbar">
      {selectedClips.length > 1 ? (
         <div className="flex flex-col h-full p-4">
            <h2 className="text-sm font-medium tracking-tight flex items-center gap-2 text-white mb-4">
               <Layers className="w-4 h-4 text-red-500" />
               Batch Processing ({selectedClips.length})
            </h2>
            <div className="flex flex-col gap-2 mb-6">
              <label htmlFor="batch_prompt" className="text-xs font-medium text-neutral-300">
                Instruction for all ({selectedClips.length} clips)
              </label>
              <textarea
                id="batch_prompt"
                placeholder={`e.g., 'Cut out silences' or 'Keep action sequences'`}
                value={selectedClips[0]?.prompt || ''}
                onChange={(e) => {
                   selectedClips.forEach(c => updateClip(c.id, { prompt: e.target.value }));
                }}
                className="w-full h-24 bg-black border border-neutral-800 rounded-lg p-3 text-xs focus:outline-none focus:border-red-500/50 focus:ring-1 focus:ring-red-500/50 resize-none transition-all placeholder:text-neutral-700"
              />
              <button
                 disabled={isProcessing || !selectedClips[0]?.prompt?.trim()}
                 onClick={onAnalyzeClip}
                 className="w-full bg-white text-black py-2.5 rounded-lg font-medium text-xs flex items-center justify-center hover:bg-neutral-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-md"
              >
                 {isProcessing ? 'Processing All...' : 'Batch Generate Edits'}
              </button>
              {error && (
                 <div className="mt-2 text-xs text-red-500 bg-red-500/10 border border-red-500/20 rounded p-2">
                   {error}
                 </div>
              )}
            </div>
         </div>
      ) : selectedClip ? (
         <div className="flex flex-col h-full p-4">
            <h2 className="text-sm font-medium tracking-tight flex items-center gap-2 text-white mb-4">
               {selectedClip.type === 'video' ? <Film className="w-4 h-4 text-red-500" /> : <Mic className="w-4 h-4 text-red-500" />}
               Edit {selectedClip.type === 'video' ? 'Video' : 'Audio'} Clip
            </h2>
            <p className="text-xs text-neutral-400 mb-4 font-mono truncate">{selectedClip.id}</p>

            <div className="flex flex-col gap-2 mb-6">
              <label htmlFor="prompt" className="text-xs font-medium text-neutral-300">
                Instruction ({selectedClip.type})
              </label>
              <textarea
                id="prompt"
                placeholder={`e.g., 'Cut out silences' or 'Keep parts where user smiles'`}
                value={selectedClip.prompt || ''}
                onChange={(e) => updateClip(selectedClip.id, { prompt: e.target.value })}
                className="w-full h-24 bg-black border border-neutral-800 rounded-lg p-3 text-xs focus:outline-none focus:border-red-500/50 focus:ring-1 focus:ring-red-500/50 resize-none transition-all placeholder:text-neutral-700"
              />
              <button
                 disabled={isProcessing || !selectedClip.prompt?.trim()}
                 onClick={onAnalyzeClip}
                 className="w-full bg-white text-black py-2.5 rounded-lg font-medium text-xs flex items-center justify-center hover:bg-neutral-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-md"
              >
                 {isProcessing ? 'Processing...' : 'Generate Edits'}
              </button>
              {error && (
                 <div className="mt-2 text-xs text-red-500 bg-red-500/10 border border-red-500/20 rounded p-2">
                   {error}
                 </div>
              )}
            </div>

            {selectedClip.type === 'video' && (
               <div className="border-t border-neutral-800 pt-4 pb-4">
                  <h3 className="text-xs font-medium text-neutral-300 mb-4">Filters</h3>
                  <div className="space-y-4">
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Brightness</span>
                           <span>{selectedClip.filters?.brightness || 1}</span>
                        </label>
                        <input type="range" min="0" max="2" step="0.1"
                           value={selectedClip.filters?.brightness || 1}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, brightness: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Contrast</span>
                           <span>{selectedClip.filters?.contrast || 1}</span>
                        </label>
                        <input type="range" min="0" max="2" step="0.1"
                           value={selectedClip.filters?.contrast || 1}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, contrast: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Saturation</span>
                           <span>{selectedClip.filters?.saturation || 1}</span>
                        </label>
                        <input type="range" min="0" max="2" step="0.1"
                           value={selectedClip.filters?.saturation || 1}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, saturation: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Sepia</span>
                           <span>{selectedClip.filters?.sepia || 0}%</span>
                        </label>
                        <input type="range" min="0" max="100" step="1"
                           value={selectedClip.filters?.sepia || 0}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, sepia: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Hue Rotate</span>
                           <span>{selectedClip.filters?.hueRotate || 0}°</span>
                        </label>
                        <input type="range" min="0" max="360" step="1"
                           value={selectedClip.filters?.hueRotate || 0}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, hueRotate: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Invert</span>
                           <span>{selectedClip.filters?.invert || 0}%</span>
                        </label>
                        <input type="range" min="0" max="100" step="1"
                           value={selectedClip.filters?.invert || 0}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, invert: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Grayscale</span>
                           <span>{selectedClip.filters?.grayscale || 0}%</span>
                        </label>
                        <input type="range" min="0" max="100" step="1"
                           value={selectedClip.filters?.grayscale || 0}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, grayscale: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div>
                        <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                           <span>Blur</span>
                           <span>{selectedClip.filters?.blur || 0}px</span>
                        </label>
                        <input type="range" min="0" max="20" step="1"
                           value={selectedClip.filters?.blur || 0}
                           onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, blur: Number(e.target.value) }})}
                           className="w-full accent-neutral-500" />
                     </div>
                     <div className="pt-2 border-t border-neutral-800">
                        <label className="text-[10px] text-red-500 font-semibold uppercase mb-2 block">AI Video Effects</label>
                        <div className="grid grid-cols-2 gap-2">
                           <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, sepia: 80, contrast: 1.2, brightness: 0.9, blur: 1, grayscale: 20 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Vintage 80s</button>
                           <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, invert: 100, hueRotate: 90, contrast: 1.5 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Neon Cyber</button>
                           <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, grayscale: 100, contrast: 1.4, brightness: 1.1 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Noir Film</button>
                           <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, sepia: 0, invert: 0, grayscale: 0, blur: 0, hueRotate: 0, contrast: 1, brightness: 1, saturation: 1 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Reset All</button>
                        </div>
                     </div>
                  </div>
               </div>
            )}

            <div className="border-t border-neutral-800 pt-4 pb-4">
               <h3 className="text-xs font-medium text-neutral-300 mb-4">Audio</h3>
               <div className="space-y-4">
                  <div>
                     <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                        <span>Volume</span>
                        <span>{(selectedClip.filters?.volume ?? 1) * 100}%</span>
                     </label>
                     <input type="range" min="0" max="2" step="0.05"
                        value={selectedClip.filters?.volume ?? 1}
                        onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, volume: Number(e.target.value) }})}
                        className="w-full accent-neutral-500" />
                  </div>
                  <div>
                     <label className="text-[10px] text-neutral-500 flex justify-between mb-1">
                        <span>Pan (L/R)</span>
                        <span>{selectedClip.filters?.pan === 0 || selectedClip.filters?.pan === undefined ? 'Center' : (selectedClip.filters?.pan > 0 ? `R ${(selectedClip.filters.pan * 100).toFixed(0)}%` : `L ${(-selectedClip.filters.pan * 100).toFixed(0)}%`)}</span>
                     </label>
                     <input type="range" min="-1" max="1" step="0.05"
                        value={selectedClip.filters?.pan ?? 0}
                        onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, pan: Number(e.target.value) }})}
                        className="w-full accent-neutral-500" />
                  </div>
                  <label className="flex items-center gap-2 text-[10px] text-neutral-400">
                     <input type="checkbox" checked={selectedClip.filters?.normalize ?? false} onChange={e => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, normalize: e.target.checked }})} className="accent-neutral-500 rounded bg-neutral-900 border-neutral-800" />
                     Normalize Audio (Peak)
                  </label>
                  
                  <div className="pt-2 border-t border-neutral-800">
                     <label className="text-[10px] text-red-500 font-semibold uppercase mb-2 block">AI Audio Effects</label>
                     <div className="grid grid-cols-2 gap-2">
                        <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, volume: 0.8, pan: 0 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Crystal Clear</button>
                        <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, pan: -0.5, volume: 1.2 }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Wide Stereo</button>
                        <button onClick={() => updateClip(selectedClip.id, { filters: { ...selectedClip.filters, volume: 1, pan: 0, normalize: false }})} className="bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-[10px] py-1 rounded">Reset All</button>
                     </div>
                  </div>
               </div>
            </div>

            <div className="border-t border-neutral-800 pt-4 flex-1">
               <h3 className="text-xs font-medium text-neutral-300 flex items-center justify-between mb-3">
                  <span>Keyframes</span>
               </h3>
               
               <div className="space-y-4">
                  <div>
                     <label className="text-[10px] text-neutral-500 uppercase tracking-wider mb-2 block">Property</label>
                     <select 
                        value={activeProperty}
                        onChange={(e) => setActiveProperty(e.target.value as any)}
                        className="w-full bg-black border border-neutral-800 rounded px-3 py-2 text-xs focus:border-white focus:outline-none text-neutral-300"
                     >
                        <option value="opacity">Opacity</option>
                        <option value="scale">Scale</option>
                        <option value="volume">Volume</option>
                     </select>
                  </div>

                  <button 
                     onClick={() => onAddKeyframe?.(selectedClip.id, activeProperty)}
                     className="w-full py-1.5 border border-neutral-700 hover:border-neutral-500 text-neutral-300 text-xs rounded transition-colors flex justify-center items-center gap-1"
                  >
                     <Diamond className="w-3 h-3" /> Add Keyframe 
                  </button>

                  <div className="space-y-2 mt-4">
                     {selectedClip.keyframes.filter(k => k.property === activeProperty).map((kf, i) => (
                        <div key={kf.id} className="bg-black/50 border border-neutral-800 rounded p-3">
                           <div className="flex items-center justify-between mb-2">
                              <span className="text-[10px] text-neutral-400 font-mono">Time: {kf.time.toFixed(2)}s</span>
                              <button onClick={() => onRemoveKeyframe?.(selectedClip.id, kf.id)} className="text-neutral-500 hover:text-red-400">×</button>
                           </div>
                           <div className="mb-3">
                              <label className="text-[10px] text-neutral-500 block mb-1">Value</label>
                              <input 
                                 type="range"
                                 min="0"
                                 max="1"
                                 step="0.01"
                                 value={kf.value}
                                 onChange={(e) => onUpdateKeyframe?.(selectedClip.id, kf.id, { value: Number(e.target.value) })}
                                 className="w-full accent-neutral-500"
                              />
                           </div>
                           <div>
                              <label className="text-[10px] text-neutral-500 flex justify-between">
                                 <span>Easing</span>
                                 <span className="font-mono text-[8px]">{kf.easing.map(n => n.toFixed(2)).join(', ')}</span>
                              </label>
                              <div className="mt-1 h-24 bg-neutral-950 border border-neutral-800 rounded relative">
                                 <CurveEditor 
                                    value={kf.easing} 
                                    onChange={(easing) => onUpdateKeyframe?.(selectedClip.id, kf.id, { easing })}
                                 />
                              </div>
                           </div>
                        </div>
                     ))}
                     {selectedClip.keyframes.filter(k => k.property === activeProperty).length === 0 && (
                        <div className="text-center py-4 text-xs text-neutral-600">No keyframes for {activeProperty}</div>
                     )}
                  </div>
               </div>
            </div>
            
         </div>
      ) : (
         <div className="flex flex-col h-full p-4">
            <h2 className="text-sm font-medium tracking-tight flex items-center gap-2 text-white mb-6">
               <Settings2 className="w-4 h-4 text-neutral-400" />
               Global Settings
            </h2>
            
            <div className="space-y-6 flex-1">
               <div className="space-y-2">
                  <label className="text-xs font-medium text-neutral-400">Aspect Ratio</label>
                  <div className="grid grid-cols-2 gap-2">
                     {['16:9', '9:16', '1:1', 'Original'].map(ratio => (
                        <button
                           key={ratio}
                           onClick={() => setGlobalSettings({...globalSettings, aspectRatio: ratio as any})}
                           className={cn(
                              "py-2 px-1 text-xs rounded border transition-colors",
                              globalSettings.aspectRatio === ratio 
                              ? "bg-white text-black border-white" 
                              : "bg-black text-neutral-400 border-neutral-800 hover:border-neutral-600"
                           )}
                        >
                           {ratio}
                        </button>
                     ))}
                  </div>
               </div>

               <div className="space-y-2">
                  <label className="text-xs font-medium text-neutral-400">Quality</label>
                  <select 
                     value={globalSettings.quality}
                     onChange={(e) => setGlobalSettings({...globalSettings, quality: e.target.value as any})}
                     className="w-full bg-black border border-neutral-800 rounded px-3 py-2 text-xs focus:border-white focus:outline-none"
                  >
                     <option value="Original">Original</option>
                     <option value="4K">4K Ultra HD</option>
                     <option value="1080p">1080p Full HD</option>
                     <option value="720p">720p HD</option>
                  </select>
               </div>

               <div className="space-y-3">
                  <label className="text-xs font-medium text-neutral-400 flex items-center gap-1">
                     <Crop className="w-3 h-3" /> Crop Origin (0-100%)
                  </label>
                  <div className="grid grid-cols-2 gap-2">
                     <div className="flex flex-col gap-1">
                        <span className="text-[10px] text-neutral-500">X pos %</span>
                        <input type="number" 
                           value={globalSettings.crop.x} 
                           onChange={e => setGlobalSettings({...globalSettings, crop: {...globalSettings.crop, x: Number(e.target.value)}})}
                           className="bg-black border border-neutral-800 rounded px-2 py-1.5 text-xs text-white" />
                     </div>
                     <div className="flex flex-col gap-1">
                        <span className="text-[10px] text-neutral-500">Y pos %</span>
                        <input type="number" 
                           value={globalSettings.crop.y} 
                           onChange={e => setGlobalSettings({...globalSettings, crop: {...globalSettings.crop, y: Number(e.target.value)}})}
                           className="bg-black border border-neutral-800 rounded px-2 py-1.5 text-xs text-white" />
                     </div>
                     <div className="flex flex-col gap-1">
                        <span className="text-[10px] text-neutral-500">Width %</span>
                        <input type="number" 
                           value={globalSettings.crop.w} 
                           onChange={e => setGlobalSettings({...globalSettings, crop: {...globalSettings.crop, w: Number(e.target.value)}})}
                           className="bg-black border border-neutral-800 rounded px-2 py-1.5 text-xs text-white" />
                     </div>
                     <div className="flex flex-col gap-1">
                        <span className="text-[10px] text-neutral-500">Height %</span>
                        <input type="number" 
                           value={globalSettings.crop.h} 
                           onChange={e => setGlobalSettings({...globalSettings, crop: {...globalSettings.crop, h: Number(e.target.value)}})}
                           className="bg-black border border-neutral-800 rounded px-2 py-1.5 text-xs text-white" />
                     </div>
                  </div>
               </div>
            </div>
         </div>
      )}
    </div>
  );
}
