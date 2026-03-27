/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        navy: '#1A3A5C',
        'navy-light': '#2D5A8C',
        'navy-dark': '#0F2438',
      },
    },
  },
  plugins: [],
}
