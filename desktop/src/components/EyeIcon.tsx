/**
 * 显隐小眼睛：睁眼 = 显示（画线 / 均线 / 某一条均线），闭眼（加一道斜杠）= 隐藏。
 *
 * 抽成独立组件是因为它现在有**两处**用处 —— 指标栏上画线的整图开关，
 * 以及均线面板里每条均线自己的小眼睛；两处必须长得一模一样，
 * 否则同一个「藏起来」在两个地方会有两种视觉语言。
 */
export function EyeIcon({ off, size = 13 }: { off: boolean; size?: number }) {
  return (
    <svg
      viewBox="0 0 16 16"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.3}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M1.6 8C3.2 5.2 5.4 3.8 8 3.8s4.8 1.4 6.4 4.2c-1.6 2.8-3.8 4.2-6.4 4.2S3.2 10.8 1.6 8Z" />
      {/* 瞳孔实心：描边圆在 12~13px 下只剩一圈细环，中间的空心会被读成一个白点 */}
      {off ? (
        <path d="M2.9 13.1 13.1 2.9" />
      ) : (
        <circle cx="8" cy="8" r="1.9" fill="currentColor" stroke="none" />
      )}
    </svg>
  );
}
