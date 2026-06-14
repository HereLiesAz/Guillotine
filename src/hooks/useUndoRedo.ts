import { useState, useCallback } from 'react';

export function useUndoRedo<T>(initialState: T) {
  const [history, setHistory] = useState<T[]>([initialState]);
  const [pointer, setPointer] = useState(0);

  const setState = useCallback((newState: T | ((prev: T) => T)) => {
    setHistory(prevHistory => {
      const current = prevHistory[pointer];
      const nextState = typeof newState === 'function' ? (newState as Function)(current) : newState;
      
      const newHistory = prevHistory.slice(0, pointer + 1);
      newHistory.push(nextState);
      setPointer(newHistory.length - 1);
      return newHistory;
    });
  }, [pointer]);

  const undo = useCallback(() => {
    setPointer(prev => Math.max(0, prev - 1));
  }, []);

  const redo = useCallback(() => {
    setPointer(prev => Math.min(history.length - 1, prev + 1));
  }, [history.length]);

  return [history[pointer], setState, undo, redo, pointer > 0, pointer < history.length - 1] as const;
}
