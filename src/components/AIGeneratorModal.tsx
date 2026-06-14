import React, { useState } from 'react';
import { X, AlertCircle, Loader2 } from 'lucide-react';
import { cn } from '../lib/utils';
import { MediaFile, TimelineClip } from '../types';

interface AIGeneratorModalProps {
   isOpen: boolean;
   onClose: () => void;
   onGenerate: (url: string, name: string) => void;
   onEdit?: (clipId: string, url: string, name: string) => void;
   selectedClip?: TimelineClip | null;
}

export default function AIGeneratorModal({ isOpen, onClose, onGenerate, onEdit, selectedClip }: AIGeneratorModalProps) {
    const [prompt, setPrompt] = useState("");
    const [provider, setProvider] = useState("pollinations");
    const [apiKey, setApiKey] = useState("");
    const [isGenerating, setIsGenerating] = useState(false);
    const [error, setError] = useState("");

    if (!isOpen) return null;

    const isEditMode = !!selectedClip;

    const handleGenerate = async () => {
        if (!prompt.trim()) return;
        setIsGenerating(true);
        setError("");

        try {
            let url = "";
            let name = isEditMode ? `AI Edited - ${prompt.slice(0, 20)}...` : `Generated - ${prompt.slice(0, 20)}...`;

            if (provider === "pollinations") {
                // Free image generation acting as media clip
                url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=1920&height=1080&nologo=play`;
            } else if (provider === "mock") {
                 // Mock video generator just resolves to a static free sample source
                 await new Promise(r => setTimeout(r, 1500));
                 url = "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4";
            } else {
                if (!apiKey) {
                    throw new Error("API key is required for this provider.");
                }
                // Simulate an API call wait
                await new Promise(r => setTimeout(r, 2000));
                // Just fallback to some mock for the UI demo since we don't have real integrations built-in for all
                url = "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";
            }

            if (isEditMode && onEdit && selectedClip) {
               onEdit(selectedClip.id, url, name);
            } else {
               onGenerate(url, name);
            }
            onClose();
        } catch (err: any) {
             setError(err.message || "Failed to generate/edit clip");
        } finally {
             setIsGenerating(false);
        }
    };

    return (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[100] flex items-center justify-center p-4">
            <div className="bg-neutral-900 border border-neutral-800 rounded-xl shadow-2xl w-full max-w-lg overflow-hidden flex flex-col">
                <div className="flex items-center justify-between p-4 border-b border-neutral-800">
                    <h2 className="text-sm font-medium tracking-tight text-white flex items-center gap-2">
                        <span className="text-red-500 font-bold text-xs tracking-wider">AI</span>
                        {isEditMode ? 'AI Clip Editor' : 'AI Clip Generator'}
                    </h2>
                    <button onClick={onClose} className="p-1 hover:bg-neutral-800 rounded text-neutral-400 max-w-2xl"><X className="w-4 h-4" /></button>
                </div>
                
                <div className="p-4 flex flex-col gap-4">
                    {isEditMode && (
                       <div className="bg-neutral-800/50 border border-neutral-800 p-2 rounded text-xs text-neutral-300">
                          Editing clip <span className="font-mono text-neutral-400">{selectedClip.id.slice(0, 8)}</span>
                       </div>
                    )}
                    <div className="space-y-1">
                        <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">Generation Model</label>
                        <select 
                            value={provider} 
                            onChange={(e) => setProvider(e.target.value)}
                            className="w-full bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white"
                        >
                            <option value="pollinations">Pollinations.ai Image (Free, No Key)</option>
                            <option value="mock">Local Mock Video (Free, Fast)</option>
                            <option value="openai">OpenAI Sora (API Key Required)</option>
                            <option value="runway">Runway Gen-3 (API Key Required)</option>
                            <option value="luma">Luma Dream Machine (API Key Required)</option>
                            <option value="pika">Pika (API Key Required)</option>
                        </select>
                    </div>

                    {(provider !== "pollinations" && provider !== "mock") && (
                        <div className="space-y-1">
                            <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">API Key</label>
                            <input 
                                type="password" 
                                value={apiKey} 
                                onChange={(e) => setApiKey(e.target.value)}
                                placeholder={`Enter your ${provider.toUpperCase()} API key...`}
                                className="w-full bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white"
                            />
                        </div>
                    )}

                    <div className="space-y-1">
                        <label className="text-xs font-semibold text-neutral-400 tracking-tight uppercase">{isEditMode ? 'Edit Prompt' : 'Generation Prompt'}</label>
                        <textarea 
                            value={prompt} 
                            onChange={(e) => setPrompt(e.target.value)}
                            placeholder={isEditMode ? "Describe how you want to edit this clip via AI..." : "Describe the clip you want to generate..."}
                            className="w-full h-24 bg-black border border-neutral-800 rounded p-2 text-xs focus:outline-none focus:border-red-500 focus:ring-1 focus:ring-red-500 transition-colors text-white resize-none"
                        />
                    </div>

                    {error && (
                        <div className="text-xs text-red-400 bg-red-400/10 p-2 rounded flex items-center gap-2">
                           <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                           {error}
                        </div>
                    )}
                </div>

                <div className="p-4 border-t border-neutral-800 flex justify-end gap-2 bg-neutral-950">
                    <button 
                        onClick={onClose}
                        className="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors font-medium"
                    >
                        Cancel
                    </button>
                    <button 
                        disabled={isGenerating || !prompt.trim()}
                        onClick={handleGenerate}
                        className="px-4 py-1.5 text-xs bg-red-600 hover:bg-red-500 text-white rounded font-medium disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
                    >
                        {isGenerating ? <><Loader2 className="w-3.5 h-3.5 animate-spin"/> Processing...</> : (isEditMode ? "Apply AI Edit" : "Generate Clip")}
                    </button>
                </div>
            </div>
        </div>
    );
}
