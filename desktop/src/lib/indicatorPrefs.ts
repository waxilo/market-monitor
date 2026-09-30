/**
 * 看盘时叠了哪些指标（均线期数 / 布林带开关 / 副图）—— 存 localStorage，重启后原样回来。
 *
 * 存的只是**选择**：MA 的期数集合固定五档、BOLL 恒 20/2、副图参数各自内置，
 * 所以存储值就是「一个数组 + 一个开关 + 一个数组」。读的时候逐项对着当前支持集校验，
 * 认不出的（老版本存的、手改坏的、以后删掉的档位）一律丢掉 —— 与 `lib/intervals.ts`
 * 的 `parseIntervals` 同一套容错口径，坏值不会把界面弄崩。
 */

import { MA_CHOICES, SUB_PANE_KINDS, type SubPaneKind } from './chartSeries';

export const INDICATORS_KEY = 'mm.indicators';

export interface IndicatorPrefs {
  /** 叠加的均线期数（升序、去重）。 */
  ma: number[];
  boll: boolean;
  /** 副图，可多选（按 `SUB_PANE_KINDS` 的声明顺序，空 = 不显示）。 */
  panes: SubPaneKind[];
}

/** 出厂 = 裸 K 线：一个指标都不叠。 */
function none(): IndicatorPrefs {
  return { ma: [], boll: false, panes: [] };
}

/** 存形：`{"ma":[5,20],"boll":true,"panes":["VOLUME"]}`。空/坏值回落裸 K 线。 */
export function parseIndicatorPrefs(raw: string | null): IndicatorPrefs {
  if (!raw) return none();
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return none();
  }
  if (parsed == null || typeof parsed !== 'object') return none();
  const o = parsed as { ma?: unknown; boll?: unknown; panes?: unknown };
  const ma = Array.isArray(o.ma)
    ? [...new Set(o.ma.filter((p): p is number => typeof p === 'number' && MA_CHOICES.includes(p)))]
        .sort((a, b) => a - b)
    : [];
  const panes = Array.isArray(o.panes)
    ? SUB_PANE_KINDS.filter((k) => (o.panes as unknown[]).includes(k))
    : [];
  return { ma, boll: o.boll === true, panes };
}

export function serializeIndicatorPrefs(prefs: IndicatorPrefs): string {
  return JSON.stringify(prefs);
}
