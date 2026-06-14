export interface EditSegment {
  start: number;
  end: number;
  action: "keep" | "remove";
  reason: string;
}

export interface MediaFile {
  id: string;
  file?: File;
  name: string;
  isRemote: boolean;
  url: string;
  duration: number;
}

export interface TimelineKeyframe {
  id: string;
  time: number;
  value: number;
  property: "opacity" | "scale" | "volume";
  easing: [number, number, number, number]; // bezier control points
}

export interface ClipFilters {
  brightness: number;
  contrast: number;
  saturation: number;
  sepia?: number;
  blur?: number;
  volume?: number;
  pan?: number;
  normalize?: boolean;
  hueRotate?: number;
  invert?: number;
  grayscale?: number;
  customCss?: string;
}

export interface TimelineClip {
  id: string;
  mediaId: string;
  type: "video" | "audio";
  trackId: string;
  startTime: number; // Start time on the timeline
  trimStart: number; // Start time within the media file
  duration: number; // Duration of the clip
  prompt: string;
  edits: EditSegment[]; // AI generated edits
  keyframes: TimelineKeyframe[];
  filters: ClipFilters;
  isAnalyzing: boolean;
  error?: string;
}

export interface GlobalSettings {
  aspectRatio: "16:9" | "9:16" | "1:1" | "Original";
  quality: "1080p" | "720p" | "4K" | "Original";
  crop: { x: number; y: number; w: number; h: number };
}

