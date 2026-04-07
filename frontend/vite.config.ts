import path from "path";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],

  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },

  // Proxy API calls to Spring Boot during local dev
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
      },
    },
  },

  build: {
    target: "es2020",
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes("node_modules")) {
            // Charts (heaviest — split first)
            if (id.includes("recharts")) return "recharts";
            // Radix UI primitives
            if (id.includes("@radix-ui")) return "radix";
            // Form stack
            if (
              id.includes("react-hook-form") ||
              id.includes("@hookform") ||
              id.includes("/zod/")
            )
              return "forms";
            // Router
            if (id.includes("react-router")) return "router";
            // TanStack Query
            if (id.includes("@tanstack")) return "query";
            // React core
            if (id.includes("/react/") || id.includes("/react-dom/"))
              return "react";
          }
        },
      },
    },
  },

  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
  },
});
