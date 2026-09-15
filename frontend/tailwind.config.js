/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        mesh: {
          dark: '#0d1117',
          card: '#161b22',
          border: '#30363d',
          accent: '#238636',
          blue: '#1f6feb',
          amber: '#d29922',
          crimson: '#da3633'
        }
      }
    },
  },
  plugins: [],
}
