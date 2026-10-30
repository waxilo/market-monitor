import { useEffect, useRef } from 'react';
import { MARKET_LABEL } from '../lib/api';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { displaySymbol } from '../lib/format';
import { LockIcon } from './icons';

interface Props {
  tabs: WatchItem[];
  activeKey: string | null;
  /** 被锁住的标签页（关不掉，包括「关闭全部」）。 */
  locked: ReadonlySet<string>;
  onActivate: (key: string) => void;
  onClose: (key: string) => void;
  onCloseAll: () => void;
  onToggleLock: (key: string) => void;
}

/**
 * 标签条：一个标签 = 一个「市场 + 标的」的图，按打开顺序排。
 *
 * 像浏览器那样：点标签切过去、× 关掉、鼠标中键也能关、锁住的那枚不出 ×（点了也没反应）；
 * 右边一枚「关闭全部」把没锁的一次收掉。标签条本身不决定「看哪个」（那是 `active`），
 * 也不存任何东西 —— 列表、当前项、锁都归 `hooks/useTabs` 管。
 *
 * 市场写在标签里（现 / 永）：现货与永续混在同一条里，同名标的（BTC_USDT）会出现两次，
 * 没有这个记号就分不清哪个是哪个。
 */
export function TabStrip({
  tabs,
  activeKey,
  locked,
  onActivate,
  onClose,
  onCloseAll,
  onToggleLock,
}: Props) {
  const activeRef = useRef<HTMLDivElement>(null);

  // 从搜索里打开一个很靠后的标签页时，把它滚进可视区（否则高亮在屏幕外，像没打开）
  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [activeKey]);

  return (
    <div className="tabstrip">
      <div className="tabstrip-scroll">
        {tabs.map((tab) => {
          const key = watchKey(tab);
          const isLocked = locked.has(key);
          const on = key === activeKey;
          return (
            <div
              key={key}
              ref={on ? activeRef : undefined}
              className={`tab${on ? ' on' : ''}${isLocked ? ' locked' : ''}`}
              title={`${displaySymbol(tab.symbol)} · ${MARKET_LABEL[tab.market]}${
                isLocked ? ' · 已锁定（不能关闭）' : ''
              }`}
              onClick={() => onActivate(key)}
              // 浏览器肌肉记忆：鼠标中键 = 关掉这个标签（锁住的由 useTabs 挡回去）
              onAuxClick={(e) => {
                if (e.button !== 1) return;
                e.preventDefault();
                onClose(key);
              }}
            >
              <span className="tab-market">{tab.market === 'FUTURES' ? '永' : '现'}</span>
              <span className="tab-name">{displaySymbol(tab.symbol)}</span>
              <button
                type="button"
                className={`tab-btn tab-lock${isLocked ? ' on' : ''}`}
                aria-pressed={isLocked}
                title={isLocked ? '解锁：允许关闭这个标签页' : '锁定：不会被关掉（含「关闭全部」）'}
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleLock(key);
                }}
              >
                <LockIcon open={!isLocked} />
              </button>
              {/* 锁住就没有 ×：不给一个点了没反应的按钮，锁图标自己就是「要关先解锁」 */}
              {!isLocked && (
                <button
                  type="button"
                  className="tab-btn tab-close"
                  title={`关闭 ${displaySymbol(tab.symbol)}（中键同效）`}
                  onClick={(e) => {
                    e.stopPropagation();
                    onClose(key);
                  }}
                >
                  ✕
                </button>
              )}
            </div>
          );
        })}

        {/* 空标签条什么都不写：下面那张卡片的空态已经把「怎么打开」说完了，
            这里再写一句只会重复。留一条 32px 的空行，版面不跳。 */}
      </div>

      {tabs.length > 0 && (
        <button
          type="button"
          className="chip tab-all"
          disabled={tabs.every((t) => locked.has(watchKey(t)))}
          title="关闭所有未锁定的标签页（锁住的不动）"
          onClick={onCloseAll}
        >
          关闭全部
        </button>
      )}
    </div>
  );
}
