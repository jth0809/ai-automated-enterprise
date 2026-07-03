import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Local dev only. In-cluster, nginx proxies /api to the backend
    // service (see nginx.conf).
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
