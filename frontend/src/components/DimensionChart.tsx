import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import { DimensionScore } from '../types';

interface DimensionChartProps {
  data: DimensionScore[];
}

export const DimensionChart = ({ data }: DimensionChartProps) => {
  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={data} margin={{ top: 20, right: 30, left: 0, bottom: 60 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis
          dataKey="dimension"
          angle={-45}
          textAnchor="end"
          height={100}
          interval={0}
        />
        <YAxis domain={[0, 100]} />
        <Tooltip />
        <Bar dataKey="score" fill="#2563EB" />
      </BarChart>
    </ResponsiveContainer>
  );
};
