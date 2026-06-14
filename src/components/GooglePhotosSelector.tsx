import React, { useState, useEffect } from 'react';
import { useGoogleLogin } from '@react-oauth/google';
import { Loader2, Image as ImageIcon, X, Check } from 'lucide-react';

export interface GooglePhotoItem {
  id: string;
  filename: string;
  baseUrl: string;
  mimeType: string;
}

interface GooglePhotosSelectorProps {
  onSelect: (items: GooglePhotoItem[]) => void;
  onClose: () => void;
}

export default function GooglePhotosSelector({ onSelect, onClose }: GooglePhotosSelectorProps) {
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<GooglePhotoItem[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const login = useGoogleLogin({
    onSuccess: (codeResponse) => {
      setToken(codeResponse.access_token);
    },
    onError: (error) => {
      setError("Login failed: " + error.error_description);
    },
    scope: "https://www.googleapis.com/auth/photoslibrary.readonly",
  });

  useEffect(() => {
    if (token) {
      fetchPhotos();
    }
  }, [token]);

  const fetchPhotos = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('https://photoslibrary.googleapis.com/v1/mediaItems:search', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          filters: {
            mediaTypeFilter: {
              mediaTypes: ["VIDEO"]
            }
          },
          pageSize: 50
        })
      });

      if (!res.ok) {
        throw new Error('Failed to fetch photos: ' + await res.text());
      }

      const data = await res.json();
      setItems(data.mediaItems || []);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  const toggleSelect = (id: string) => {
    const newSelected = new Set(selectedIds);
    if (newSelected.has(id)) {
      newSelected.delete(id);
    } else {
      if (newSelected.size >= 10) {
         alert("Maximum 10 clips allowed.");
         return;
      }
      newSelected.add(id);
    }
    setSelectedIds(newSelected);
  };

  const handleConfirm = () => {
    const selected = items.filter(item => selectedIds.has(item.id));
    onSelect(selected);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4 backdrop-blur-sm">
      <div className="bg-neutral-900 border border-neutral-800 rounded-2xl w-full max-w-4xl h-[80vh] flex flex-col overflow-hidden shadow-2xl">
        <div className="p-4 border-b border-neutral-800 flex items-center justify-between">
          <h2 className="text-xl font-medium tracking-tight">Select from Google Photos</h2>
          <button onClick={onClose} className="p-2 hover:bg-neutral-800 rounded-full transition-colors"><X className="w-5 h-5"/></button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 custom-scrollbar">
          {!token ? (
             <div className="h-full flex flex-col items-center justify-center text-center">
                <ImageIcon className="w-16 h-16 text-red-500/50 mb-4" />
                <h3 className="text-xl mb-2 font-medium">Connect Google Photos</h3>
                <p className="text-neutral-400 max-w-sm mb-6 text-sm">
                  We need your permission to view your Google Photos so you can select videos to edit.
                </p>
                <button
                  onClick={() => login()}
                  className="bg-white text-black px-6 py-2 rounded-full font-medium hover:bg-neutral-200 transition-colors"
                >
                  Sign in with Google
                </button>
                {error && <p className="text-red-400 mt-4 text-sm bg-red-400/10 px-4 py-2 rounded">{error}</p>}
             </div>
          ) : loading ? (
             <div className="h-full flex items-center justify-center">
                <Loader2 className="w-8 h-8 text-red-500 animate-spin" />
             </div>
          ) : error ? (
             <div className="h-full flex flex-col items-center justify-center">
                <p className="text-red-400 mb-4">{error}</p>
                <button onClick={fetchPhotos} className="text-red-500 hover:text-red-400">Try Again</button>
             </div>
          ) : items.length === 0 ? (
             <div className="h-full flex flex-col items-center justify-center text-neutral-500">
                <p>No videos found in your Google Photos.</p>
             </div>
          ) : (
             <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-3">
               {items.map(item => {
                 const isSelected = selectedIds.has(item.id);
                 return (
                   <div 
                     key={item.id}
                     onClick={() => toggleSelect(item.id)}
                     className={`relative aspect-square rounded-xl overflow-hidden cursor-pointer border-2 transition-all ${isSelected ? 'border-red-500 scale-95' : 'border-transparent hover:border-neutral-700'}`}
                   >
                     <img 
                       src={`${item.baseUrl}=w400-h400-c`} 
                       alt={item.filename}
                       className="w-full h-full object-cover"
                     />
                     <div className="absolute inset-0 bg-black/20 group-hover:bg-transparent transition-colors" />
                     {isSelected && (
                       <div className="absolute top-2 right-2 w-6 h-6 bg-red-500 rounded-full flex items-center justify-center">
                         <Check className="w-4 h-4 text-white" />
                       </div>
                     )}
                     <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 to-transparent p-2 text-[10px] text-white truncate">
                        {item.filename}
                     </div>
                   </div>
                 );
               })}
             </div>
          )}
        </div>

        {token && items.length > 0 && (
          <div className="p-4 border-t border-neutral-800 flex items-center justify-between bg-neutral-900/50">
            <span className="text-sm text-neutral-400">{selectedIds.size} selected</span>
            <div className="flex gap-3">
               <button onClick={onClose} className="px-5 py-2 rounded-lg text-sm font-medium text-neutral-300 hover:text-white transition-colors">Cancel</button>
               <button 
                  onClick={handleConfirm}
                  disabled={selectedIds.size === 0}
                  className="px-5 py-2 rounded-lg text-sm font-medium bg-red-600 text-white hover:bg-red-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
               >
                  Add {selectedIds.size} Video{selectedIds.size !== 1 ? 's' : ''}
               </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
