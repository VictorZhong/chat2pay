import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          red: '#db0011',
          redDark: '#b7000f',
          black: '#111111',
          charcoal: '#232323',
          gray: '#6a6a6a',
          line: '#d9d9d9',
          cloud: '#f3f3f3',
          fog: '#fafafa',
        },
      },
      boxShadow: {
        panel: '0 18px 40px rgba(17, 17, 17, 0.08)',
      },
      fontFamily: {
        sans: ['"Helvetica Neue"', 'Arial', '"PingFang SC"', '"Microsoft YaHei"', 'sans-serif'],
      },
    },
  },
  plugins: [],
} satisfies Config;
