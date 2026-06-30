export function MetricCard({ label, value, tone = 'blue' }: { label: string; value: string; tone?: 'blue' | 'green' | 'orange' }) {
  const color = tone === 'green' ? 'text-commerce-green' : tone === 'orange' ? 'text-commerce-sale' : 'text-brand-blue';
  return (
    <div className="rounded-md border border-commerce-line bg-white p-4 shadow-sm">
      <div className="text-xs font-bold text-slate-500">{label}</div>
      <div className={`mt-2 text-2xl font-black tracking-tight ${color}`}>{value}</div>
    </div>
  );
}
