import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          navy: '#111827',
          blue: '#2563eb',
          pale: '#eff6ff'
        },
        commerce: {
          bg: '#f6f7f9',
          line: '#e4e7ec',
          ink: '#111827',
          sale: '#ef3f3f',
          coral: '#ff6b4a',
          green: '#16a34a',
          amber: '#f59e0b'
        }
      },
      boxShadow: {
        panel: '0 10px 26px rgba(17, 24, 39, 0.05)',
        product: '0 14px 30px rgba(17, 24, 39, 0.08)'
      }
    }
  },
  plugins: []
} satisfies Config;
