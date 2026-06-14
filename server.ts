import express from "express";
import path from "path";
import multer from "multer";
import os from "os";
import fs from "fs";
import { GoogleGenAI, Type } from "@google/genai";
import { createServer as createViteServer } from "vite";

// Single source of truth for the Gemini model used for media analysis.
const GEMINI_MODEL = "gemini-2.5-flash";

// SSRF guard: only allow downloading remote media from known-safe hosts.
// Anything the client passes as a remoteUrl is fetched server-side, so this
// must be restrictive. Suffix match against the hostname.
const ALLOWED_REMOTE_HOSTS = [
  "googleusercontent.com",      // Google Photos baseUrl downloads
  "photoslibrary.googleapis.com",
  "storage.googleapis.com",     // sample / generated assets
  "image.pollinations.ai",      // free image generation
];

function assertSafeRemoteUrl(rawUrl: string): URL {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new Error("Invalid remote URL.");
  }
  if (url.protocol !== "https:") {
    throw new Error("Only https remote URLs are allowed.");
  }
  const host = url.hostname.toLowerCase();
  const ok = ALLOWED_REMOTE_HOSTS.some(
    (allowed) => host === allowed || host.endsWith(`.${allowed}`)
  );
  if (!ok) {
    throw new Error(`Remote host not allowed: ${host}`);
  }
  return url;
}

async function downloadRemoteToTmp(rawUrl: string, label: string): Promise<string> {
  assertSafeRemoteUrl(rawUrl);
  const resp = await fetch(rawUrl);
  if (!resp.ok) {
    throw new Error(`Failed to download remote media ${label} (${resp.status})`);
  }
  const arrayBuffer = await resp.arrayBuffer();
  if (arrayBuffer.byteLength === 0) {
    throw new Error(`Remote media ${label} was empty.`);
  }
  const tmpPath = path.join(
    os.tmpdir(),
    `remote-${Date.now()}-${Math.floor(Math.random() * 10000)}.mp4`
  );
  fs.writeFileSync(tmpPath, Buffer.from(arrayBuffer));
  return tmpPath;
}

async function startServer() {
  const app = express();
  const PORT = Number(process.env.PORT) || 3000;

  app.use(express.json());

  // Configure Multer to accept a single media upload.
  const upload = multer({ dest: os.tmpdir() });

  // Runtime client configuration. Lets the host change the Google OAuth client
  // id without rebuilding the client bundle (VITE_ vars are otherwise inlined
  // at build time).
  app.get("/api/config", (_req, res) => {
    res.json({
      googleClientId:
        process.env.VITE_GOOGLE_CLIENT_ID || process.env.GOOGLE_CLIENT_ID || "",
    });
  });

  // API Route: Analyze a SINGLE clip (Video or Audio) via Gemini.
  app.post("/api/analyze-clip", upload.single("media"), async (req, res) => {
    let pathToUse = "";
    let needsTempCleanup = false;
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

      const aiProvider = req.body.aiProvider || "gemini";

      // Honest free placeholders. These do not analyze the media; they return a
      // single keep segment so the rest of the pipeline can be exercised
      // offline / without an API key.
      if (aiProvider === "mock" || aiProvider === "local") {
        return res.json({
          edits: [
            {
              start: 0,
              end: 5,
              action: "keep",
              reason: `Placeholder segment (${aiProvider} mode does not analyze media).`,
            },
          ],
          originalName: file?.originalname || remoteName,
        });
      }

      if (aiProvider !== "gemini") {
        return res.status(400).json({
          error:
            `Provider "${aiProvider}" is not supported for media analysis. ` +
            `Use "gemini" (real) or "mock"/"local" (placeholder).`,
        });
      }

      const apiKey = process.env.GEMINI_API_KEY;
      if (!apiKey) {
        return res.status(500).json({ error: "GEMINI_API_KEY not configured on server." });
      }

      const ai = new GoogleGenAI({
        apiKey: apiKey,
        httpOptions: { headers: { "User-Agent": "aistudio-build" } },
      });

      let mimeTypeToUse = "";
      let originalNameToUse = "";

      if (remoteUrl) {
        console.log("Downloading remote media:", remoteName);
        pathToUse = await downloadRemoteToTmp(remoteUrl, remoteName);
        mimeTypeToUse = "video/mp4";
        originalNameToUse = remoteName;
        needsTempCleanup = true;
      } else if (file) {
        pathToUse = file.path;
        mimeTypeToUse = file.mimetype;
        originalNameToUse = file.originalname;
        needsTempCleanup = true;
      }

      console.log(`Starting analysis for prompt: "${prompt}" on ${originalNameToUse} with Gemini`);

      const uploadedFile = await ai.files.upload({
        file: pathToUse,
        config: { mimeType: mimeTypeToUse, displayName: originalNameToUse },
      });
      console.log(`Uploaded. URI:`, uploadedFile.uri);

      let processedFile = await ai.files.get({ name: uploadedFile.name });
      let retries = 50; // up to ~150s for longer videos
      while (processedFile.state === "PROCESSING" && retries > 0) {
        await new Promise((r) => setTimeout(r, 3000));
        processedFile = await ai.files.get({ name: uploadedFile.name });
        retries--;
      }

      if (processedFile.state === "FAILED" || processedFile.state === "PROCESSING") {
        throw new Error("Media processing failed or timed out in Gemini backend.");
      }

      console.log(`Calling generateContent...`);

      const systemPrompt =
        type === "audio"
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
        model: GEMINI_MODEL,
        contents: [
          { text: systemPrompt },
          { fileData: { fileUri: uploadedFile.uri, mimeType: uploadedFile.mimeType || "video/mp4" } },
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
                reason: { type: Type.STRING },
              },
              required: ["start", "end", "action", "reason"],
            },
          },
        },
      });

      const text = response.text;
      const edits = JSON.parse(text || "[]");

      // Best-effort remote cleanup.
      try {
        await ai.files.delete({ name: uploadedFile.name });
      } catch {}

      res.json({ edits, originalName: originalNameToUse });
    } catch (error: any) {
      console.error("error during processing:", error);
      res.status(500).json({ error: error.message || "Failed to process media processing request" });
    } finally {
      if (needsTempCleanup && pathToUse) fs.unlink(pathToUse, () => {});
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
    app.get("*", (_req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
