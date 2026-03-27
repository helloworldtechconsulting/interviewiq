interface ScoreBadgeProps {
  score: number;
  size?: 'small' | 'medium' | 'large';
}

export const ScoreBadge = ({ score, size = 'medium' }: ScoreBadgeProps) => {
  const getColor = (score: number) => {
    if (score >= 80) return 'bg-green-500 text-white';
    if (score >= 60) return 'bg-blue-500 text-white';
    if (score >= 40) return 'bg-yellow-500 text-white';
    return 'bg-red-500 text-white';
  };

  const sizeClass =
    size === 'large'
      ? 'w-24 h-24 text-4xl'
      : size === 'medium'
        ? 'w-16 h-16 text-2xl'
        : 'w-12 h-12 text-lg';

  return (
    <div
      className={`${sizeClass} ${getColor(score)} rounded-full flex items-center justify-center font-bold`}
    >
      {Math.round(score)}
    </div>
  );
};
