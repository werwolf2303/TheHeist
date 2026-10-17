import { defineConfig } from 'vite'
import { resolve } from 'path'
import fs from 'fs'

// Copies teavm-dist/ into dist/teavm-dist/ so the wasm is reachable at runtime.
// Vite's publicDir only accepts one directory, so we do it with a plugin.
function copyTeavmDist() {
    return {
        name: 'copy-teavm-dist',
        closeBundle() {
            const src = resolve(__dirname, 'teavm-dist')
            const dest = resolve(__dirname, 'dist/teavm-dist')
            fs.cpSync(src, dest, { recursive: true })
        }
    }
}

export default defineConfig({
    base: './',
    server: {
        watch: {
            usePolling: true,
        }
    },
    plugins: [copyTeavmDist()],
})
