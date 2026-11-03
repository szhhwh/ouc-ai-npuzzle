/// <reference types="vitest/config" />
import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

// A single self-contained index.html keeps the build usable from file:// and GitHub Pages alike.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss(), viteSingleFile()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  // Module workers from blob URLs fail on file:// (opaque origin); a classic worker runs everywhere.
  worker: { format: "iife" },
  test: { environment: "node" },
});
