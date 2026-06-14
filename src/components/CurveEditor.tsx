import React, { useRef, useEffect, useState } from 'react';

interface CurveEditorProps {
  value: [number, number, number, number]; // [x1, y1, x2, y2]
  onChange: (value: [number, number, number, number]) => void;
}

export default function CurveEditor({ value, onChange }: CurveEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [activeHandle, setActiveHandle] = useState<number | null>(null);

  const [x1, y1, x2, y2] = value;

  const handlePointerDown = (index: number) => (e: React.PointerEvent) => {
    e.target.setPointerCapture(e.pointerId);
    setActiveHandle(index);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (activeHandle === null || !containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    let px = (e.clientX - rect.left) / rect.width;
    let py = 1 - (e.clientY - rect.top) / rect.height;
    
    px = Math.max(0, Math.min(1, px));
    
    if (activeHandle === 1) {
      onChange([px, py, x2, y2]);
    } else {
      onChange([x1, y1, px, py]);
    }
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    e.target.releasePointerCapture(e.pointerId);
    setActiveHandle(null);
  };

  return (
    <div 
      ref={containerRef}
      className="absolute inset-0 m-4 relative overflow-visible touch-none"
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
    >
      <svg className="w-full h-full overflow-visible absolute inset-0 pointer-events-none" viewBox="0 0 100 100" preserveAspectRatio="none">
         <path 
            d={`M 0 100 C ${x1 * 100} ${(1 - y1) * 100}, ${x2 * 100} ${(1 - y2) * 100}, 100 0`}
            fill="none" 
            stroke="#10b981" 
            strokeWidth="2" 
            vectorEffect="non-scaling-stroke"
         />
         <line x1="0" y1="100" x2={x1 * 100} y2={(1 - y1) * 100} stroke="#525252" strokeWidth="1" strokeDasharray="2 2" vectorEffect="non-scaling-stroke" />
         <line x1="100" y1="0" x2={x2 * 100} y2={(1 - y2) * 100} stroke="#525252" strokeWidth="1" strokeDasharray="2 2" vectorEffect="non-scaling-stroke" />
      </svg>
      
      {/* Handle 1 */}
      <div 
         onPointerDown={handlePointerDown(1)}
         className="w-3 h-3 bg-white border border-neutral-400 rounded-full absolute -ml-1.5 -mt-1.5 cursor-move hover:scale-125 transition-transform shadow-lg"
         style={{ left: `${x1 * 100}%`, top: `${(1 - y1) * 100}%` }}
      />
      {/* Handle 2 */}
      <div 
         onPointerDown={handlePointerDown(2)}
         className="w-3 h-3 bg-white border border-neutral-400 rounded-full absolute -ml-1.5 -mt-1.5 cursor-move hover:scale-125 transition-transform shadow-lg"
         style={{ left: `${x2 * 100}%`, top: `${(1 - y2) * 100}%` }}
      />
    </div>
  );
}
