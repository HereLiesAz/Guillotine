import React, { useState } from 'react';
import { X } from 'lucide-react';

interface SettingsModalProps {
  onClose: () => void;
}

export default function SettingsModal({ onClose }: SettingsModalProps) {
  const [provider, setProvider] = useState(localStorage.getItem('aiProvider') || 'gemini');
  const [apiKey, setApiKey] = useState(localStorage.getItem('aiKey') || '');

  const handleSave = () => {
    localStorage.setItem('aiProvider', provider);
    localStorage.setItem('aiKey', apiKey);
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black/80 z-50 flex items-center justify-center p-4">
      <div className="bg-neutral-900 border border-neutral-800 rounded-lg w-full max-w-md overflow-hidden text-neutral-300 relative">
        <div className="px-4 py-3 border-b border-neutral-800 flex justify-between items-center">
           <h2 className="font-medium text-white">Settings</h2>
           <button onClick={onClose} className="hover:text-white"><X className="w-4 h-4"/></button>
        </div>
        
        <div className="p-4 space-y-6">
           <div>
              <h3 className="text-sm font-medium mb-3 text-white">AI Provider</h3>
              <div className="space-y-4">
                 <div>
                    <label className="text-xs text-neutral-500 block mb-1">Provider</label>
                    <select 
                       value={provider} 
                       onChange={e => setProvider(e.target.value)}
                       className="w-full bg-black border border-neutral-800 rounded px-3 py-2 text-xs focus:outline-none focus:border-neutral-600"
                    >
                       <option value="gemini">Google Gemini (Server Default)</option>
                       <option value="openai">OpenAI (GPT-4o)</option>
                       <option value="anthropic">Anthropic (Claude 3.5)</option>
                       <option value="mock">Basic Mock AI (Free Placeholder)</option>
                       <option value="local">Local Rule-Based Heuristic (Free)</option>
                    </select>
                 </div>
                 {(provider === 'openai' || provider === 'anthropic') && (
                    <div>
                       <label className="text-xs text-neutral-500 block mb-1">API Key</label>
                       <input 
                          type="password" 
                          value={apiKey}
                          onChange={e => setApiKey(e.target.value)}
                          placeholder="sk-..."
                          className="w-full bg-black border border-neutral-800 rounded px-3 py-2 text-xs focus:outline-none focus:border-neutral-600"
                       />
                       <p className="text-[10px] text-neutral-500 mt-1">Key is stored locally in your browser.</p>
                    </div>
                 )}
              </div>
           </div>

           <div>
              <h3 className="text-sm font-medium mb-3 text-white">Shortcut Mappings</h3>
              <div className="space-y-2 text-xs">
                 <div className="flex justify-between items-center bg-black/50 p-2 rounded border border-neutral-800 font-mono">
                    <span>Play/Pause</span>
                    <span className="text-white bg-neutral-800 px-1.5 py-0.5 rounded">Space</span>
                 </div>
                 <div className="flex justify-between items-center bg-black/50 p-2 rounded border border-neutral-800 font-mono">
                    <span>Split Clip</span>
                    <span className="text-white bg-neutral-800 px-1.5 py-0.5 rounded">S</span>
                 </div>
                 <div className="flex justify-between items-center bg-black/50 p-2 rounded border border-neutral-800 font-mono">
                    <span>Delete Select</span>
                    <span className="text-white bg-neutral-800 px-1.5 py-0.5 rounded">Del / Backspace</span>
                 </div>
                 <div className="flex justify-between items-center bg-black/50 p-2 rounded border border-neutral-800 font-mono">
                    <span>Undo</span>
                    <span className="text-white bg-neutral-800 px-1.5 py-0.5 rounded">Ctrl/Cmd + Z</span>
                 </div>
                 <div className="flex justify-between items-center bg-black/50 p-2 rounded border border-neutral-800 font-mono">
                    <span>Redo</span>
                    <span className="text-white bg-neutral-800 px-1.5 py-0.5 rounded">Ctrl/Cmd + Y</span>
                 </div>
              </div>
           </div>
        </div>

        <div className="px-4 py-3 border-t border-neutral-800 flex justify-end gap-2">
           <button onClick={onClose} className="px-4 py-1.5 rounded bg-neutral-800 hover:bg-neutral-700 text-xs text-white transition-colors">Cancel</button>
           <button onClick={handleSave} className="px-4 py-1.5 rounded bg-white text-black hover:bg-neutral-200 text-xs font-medium transition-colors">Save Settings</button>
        </div>
      </div>
    </div>
  );
}
