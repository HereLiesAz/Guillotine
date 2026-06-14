import express from "express";
import path from "path";
import multer from "multer";
import os from "os";
import fs from "fs";
import { GoogleGenAI, Type } from "@google/genai";
import OpenAI from "openai";
import Anthropic from "@anthropic-ai/sdk";
import { createServer as createViteServer } from "vite";
import { pipeline } from "stream/promises";

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json());

  // Configure Multer to accept array of videos
  const upload = multer({ dest: os.tmpdir() });

  // API Route: Upload Videos & Analyze via Gemini
  app.post("/api/analyze-videos", upload.array("videos", 10), async (req, res) => {
    try {
      const prompt = req.body.prompt;
      
      // Parse remote videos if any
      let remoteVideos: {url: string, name: string}[] = [];
      if (req.body.remoteVideos) {
        try {
           remoteVideos = JSON.parse(req.body.remoteVideos);
        } catch (e) {
           console.error("Failed to parse remote videos");
        }
      }

      const files = req.files as Express.Multer.File[] || [];

      if (files.length === 0 && remoteVideos.length === 0) {
        return res.status(400).json({ error: "No video files provided" });
      }
      if (!prompt) {
        return res.status(400).json({ error: "No prompt provided" });
      }
      
      const aiProvider = req.body.aiProvider || "gemini";
      if (aiProvider !== "gemini") {
         return res.status(400).json({ error: "Batch video upload currently only supports Google Gemini in this workspace." });
      }

      const apiKey = process.env.GEMINI_API_KEY;
      if (!apiKey) {
        return res.status(500).json({ error: "GEMINI_API_KEY not configured on server." });
      }

      const ai = new GoogleGenAI({
        apiKey: apiKey,
        httpOptions: { headers: { 'User-Agent': 'aistudio-build' } }
      });

      console.log(`Starting analysis for prompt: "${prompt}" on ${files.length + remoteVideos.length} files`);

      // Prepare items for processing: combine local files and remote downloads
      const itemsToProcess = [];
      
      for (const f of files) {
         itemsToProcess.push({
            path: f.path,
            mimeType: f.mimetype,
            originalName: f.originalname,
            needsCleanup: true
         });
      }

      for (const r of remoteVideos) {
         // Download the remote video
         console.log("Downloading remote video:", r.name);
         const resp = await fetch(r.url);
         if (!resp.ok) {
            throw new Error(`Failed to download remote video ${r.name}`);
         }
         const tmpPath = path.join(os.tmpdir(), `remote-${Date.now()}-${Math.floor(Math.random()*10000)}.mp4`);
         
         const dest = fs.createWriteStream(tmpPath);
         if (resp.body) {
            // using native Node fetch Body which is technically a ReadableStream in new Node versions
            // but we can just use arrayBuffer for small/medium files, or readable stream
            const arrayBuffer = await resp.arrayBuffer();
            fs.writeFileSync(tmpPath, Buffer.from(arrayBuffer));
         }

         itemsToProcess.push({
            path: tmpPath,
            mimeType: "video/mp4",
            originalName: r.name,
            needsCleanup: true
         });
      }

      // Process all videos
      const results = await Promise.all(
        itemsToProcess.map(async (fileObj, index) => {
          try {
            console.log(`[File ${index}] Uploading to Gemini...`);
            const uploadedFile = await ai.files.upload({
              file: fileObj.path,
              config: {
                mimeType: fileObj.mimeType,
                displayName: fileObj.originalName,
              }
            });
            console.log(`[File ${index}] Uploaded. URI:`, uploadedFile.uri);

            let processedFile = await ai.files.get({ name: uploadedFile.name });
            let retries = 50; // Increased retry count for up to 3min videos (50*3s = 150s)
            while (processedFile.state === 'PROCESSING' && retries > 0) {
              await new Promise((r) => setTimeout(r, 3000));
              processedFile = await ai.files.get({ name: uploadedFile.name });
              retries--;
            }

            if (processedFile.state === 'FAILED' || processedFile.state === 'PROCESSING') {
               throw new Error("Video processing failed or timed out in Gemini backend.");
            }

            console.log(`[File ${index}] Calling generateContent...`);
            const response = await ai.models.generateContent({
              model: "gemini-3.1-pro-preview",
              contents: [
                {
                  text: `You are an expert AI video editor. Analyze the video and the user prompt.
User Prompt: "${prompt}"

Identify the segments of the video to either 'keep' or 'remove' based on the user's instructions.
Output exactly as a JSON array where each object has:
- start (number in seconds)
- end (number in seconds)
- action ("keep" or "remove")
- reason (string explanation)

Ensure segments cover the relevant portions logically without overlaps. Be generous, leave padding around key moments.`
                },
                {
                   fileData: { fileUri: uploadedFile.uri, mimeType: uploadedFile.mimeType || "video/mp4" }
                }
              ],
              config: {
                responseMimeType: "application/json",
                responseSchema: {
                  type: Type.ARRAY,
                  items: {
                    type: Type.OBJECT,
                    properties: {
                      start: { type: Type.NUMBER, description: "Start time in seconds" },
                      end: { type: Type.NUMBER, description: "End time in seconds" },
                      action: { type: Type.STRING, description: "'keep' or 'remove'" },
                      reason: { type: Type.STRING }
                    },
                    required: ["start", "end", "action", "reason"]
                  }
                }
              }
            });

            const text = response.text;
            const edits = JSON.parse(text || "[]");
            
            // Clean up files locally and remotely
            if (fileObj.needsCleanup) fs.unlink(fileObj.path, () => {});
            try { await ai.files.delete({ name: uploadedFile.name }); } catch (e) {}

            return { index, edits, originalName: fileObj.originalName };
          } catch (err: any) {
            console.error(`[File ${index}] Error:`, err);
            if (fileObj.needsCleanup) fs.unlink(fileObj.path, () => {});
            return { index, edits: [], error: err.message, originalName: fileObj.originalName };
          }
        })
      );

      res.json({ results });
    } catch (error: any) {
      console.error("error during processing:", error);
      res.status(500).json({ error: error.message || "Failed to process video processing request" });
    }
  });

  // API Route: Analyze a SINGLE clip (Video or Audio)
  app.post("/api/analyze-clip", upload.single("media"), async (req, res) => {
    try {
      const prompt = req.body.prompt;
      const type = req.body.type || "video"; // "video" or "audio"
      
      const remoteUrl = req.body.remoteUrl;
      const remoteName = req.body.remoteName || "remote_media";
      const file = req.file;

      if (!prompt) {
        return res.status(400).json({ error: "No prompt provided" });
      }

      if (!file && !remoteUrl) {
         return res.status(400).json({ error: "No media file or remoteUrl provided" });
      }

      const apiKey = process.env.GEMINI_API_KEY;
      if (!apiKey) {
        return res.status(500).json({ error: "GEMINI_API_KEY not configured on server." });
      }

      const ai = new GoogleGenAI({
        apiKey: apiKey,
        httpOptions: { headers: { 'User-Agent': 'aistudio-build' } }
      });

      let pathToUse = "";
      let mimeTypeToUse = "";
      let originalNameToUse = "";
      let needsTempCleanup = false;

      if (remoteUrl) {
         console.log("Downloading remote media:", remoteName);
         const resp = await fetch(remoteUrl);
         if (!resp.ok) {
            throw new Error(`Failed to download remote media ${remoteName}`);
         }
         const tmpPath = path.join(os.tmpdir(), `remote-${Date.now()}-${Math.floor(Math.random()*10000)}.mp4`);
         
         if (resp.body) {
            const arrayBuffer = await resp.arrayBuffer();
            fs.writeFileSync(tmpPath, Buffer.from(arrayBuffer));
         }

         pathToUse = tmpPath;
         mimeTypeToUse = "video/mp4"; // Defaulting depending on source
         originalNameToUse = remoteName;
         needsTempCleanup = true;
      } else if (file) {
         pathToUse = file.path;
         mimeTypeToUse = file.mimetype;
         originalNameToUse = file.originalname;
         needsTempCleanup = true;
      }

      const aiProvider = req.body.aiProvider || "gemini";
      const userAiKey = req.body.aiKey;

      if (aiProvider === "mock" || aiProvider === "local") {
         return res.json({ edits: [{start: 0, end: 5, action: 'keep', reason: `Mocked via free ${aiProvider} processor`}], originalName: remoteName || file?.originalname || "media" });
      } else if (aiProvider === "openai" && userAiKey) {
         console.log("Using user-provided OpenAI key");
         const openai = new OpenAI({ apiKey: userAiKey });
         const mockRes = await openai.chat.completions.create({
            model: "gpt-4o",
            messages: [{ role: "user", content: "We are editing a video based on prompt: " + prompt + ". Return a JSON array with exactly ONE mock edit segment {start: 0, end: 5, action: 'keep', reason: 'Mocked via OpenAI'}. Output JSON ONLY." }]
         });
         const text = mockRes.choices[0].message.content || "[]";
         const cleaned = text.replace(/```json/g, '').replace(/```/g, '');
         return res.json({ edits: JSON.parse(cleaned), originalName: remoteName || "media" });
      } else if (aiProvider === "anthropic" && userAiKey) {
         console.log("Using user-provided Anthropic key");
         const anthropic = new Anthropic({ apiKey: userAiKey });
         const mockRes = await anthropic.messages.create({
            model: "claude-3-5-sonnet-20240620",
            max_tokens: 100,
            messages: [{ role: "user", content: "We are editing a video based on prompt: " + prompt + ". Return a JSON array with exactly ONE mock edit segment {start: 0, end: 5, action: 'keep', reason: 'Mocked via Anthropic'}. Output JSON ONLY." }]
         });
         const text = mockRes.content[0].type === "text" ? mockRes.content[0].text : "[]";
         const cleaned = text.replace(/```json/g, '').replace(/```/g, '');
         return res.json({ edits: JSON.parse(cleaned), originalName: remoteName || "media" });
      }

      console.log(`Starting analysis for prompt: "${prompt}" on ${originalNameToUse} with Gemini`);

      const uploadedFile = await ai.files.upload({
        file: pathToUse,
        config: {
          mimeType: mimeTypeToUse,
          displayName: originalNameToUse,
        }
      });
      console.log(`Uploaded. URI:`, uploadedFile.uri);

      let processedFile = await ai.files.get({ name: uploadedFile.name });
      let retries = 50;
      while (processedFile.state === 'PROCESSING' && retries > 0) {
        await new Promise((r) => setTimeout(r, 3000));
        processedFile = await ai.files.get({ name: uploadedFile.name });
        retries--;
      }

      if (processedFile.state === 'FAILED' || processedFile.state === 'PROCESSING') {
         throw new Error("Media processing failed or timed out in Gemini backend.");
      }

      console.log(`Calling generateContent...`);

      const systemPrompt = type === "audio" 
         ? `You are an expert AI audio editor. Analyze the audio and the user prompt.
User Prompt: "${prompt}"

Identify the segments of the audio to either 'keep' or 'remove' based on the user's instructions.
Output exactly as a JSON array where each object has:
- start (number in seconds)
- end (number in seconds)
- action ("keep" or "remove")
- reason (string explanation)

Ensure segments cover the relevant portions logically without overlaps.`
         : `You are an expert AI video editor. Analyze the video and the user prompt.
User Prompt: "${prompt}"

Identify the segments of the video to either 'keep' or 'remove' based on the user's instructions.
Output exactly as a JSON array where each object has:
- start (number in seconds)
- end (number in seconds)
- action ("keep" or "remove")
- reason (string explanation)

Ensure segments cover the relevant portions logically without overlaps. Be generous, leave padding around key moments.`;

      const response = await ai.models.generateContent({
        model: "gemini-3.1-pro-preview",
        contents: [
          { text: systemPrompt },
          { fileData: { fileUri: uploadedFile.uri, mimeType: uploadedFile.mimeType || "video/mp4" } }
        ],
        config: {
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.ARRAY,
            items: {
              type: Type.OBJECT,
              properties: {
                start: { type: Type.NUMBER, description: "Start time in seconds" },
                end: { type: Type.NUMBER, description: "End time in seconds" },
                action: { type: Type.STRING, description: "'keep' or 'remove'" },
                reason: { type: Type.STRING }
              },
              required: ["start", "end", "action", "reason"]
            }
          }
        }
      });

      const text = response.text;
      const edits = JSON.parse(text || "[]");
      
      // Clean up files locally and remotely
      if (needsTempCleanup) fs.unlink(pathToUse, () => {});
      try { await ai.files.delete({ name: uploadedFile.name }); } catch (e) {}

      res.json({ edits, originalName: originalNameToUse });
    } catch (error: any) {
      console.error("error during processing:", error);
      res.status(500).json({ error: error.message || "Failed to process media processing request" });
    }
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();

