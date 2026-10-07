/**
 * 看盘时叠了哪些指标（均线目录与期数 / 布林带开关 / 副图）—— 存 localStorage，重启后原样回来。
 *
 * 存的只是**选择**：MA 的**目录**（指标栏上有哪些 chip、什么顺序）与其中的选中子集、
 * BOLL 恒 20/2、副图参数各自内置，所以存储值就是「两个数组 + 一个开关 + 一个数组」。
 * 读的时候逐项校验，认不出的（老版本存的、手改坏的、以后删掉的档位）一律丢掉 ——
 * 与 `lib/intervals.ts` 的 `parseIntervals` 同一套容错口径，坏值不会把界面弄崩。
 */

import { SUB_PANE_KINDS, MA_LIMIT, type SubPaneKind } from './chartSeries';
import { DEFAULT_MA_CATALOG, isMaPeriodLegal } from './maPeriods';

export const INDICATORS_KEY = 'mm.indicators';

export interface IndicatorPrefs {
  /** 指标栏上出现哪些均线 chip（有序，用户可增删/排序）。 */
  maCatalog: number[];
  /** 其中**当前画出**的子集（升序、去重，且必然是目录的子集）。 */
  ma: number[];
  boll: boolean;
  /** 副图，可多选（按 `SUB_PANE_KINDS` 的声明顺序，空 = 不显示）。 */
  panes: SubPaneKind[];
}

/** 出厂 = 裸 K 线：目录是内置五档、一个都不叠。 */
function none(): IndicatorPrefs {
  return { maCatalog: [...DEFAULT_MA_CATALOG], ma: [], boll: false, panes: [] };
}

/** 存形：`{"maCatalog":[5,10,20,30,60],"ma":[5,20],"boll":true,"panes":["VOLUME"]}`。空/坏值回落出厂。 */
export function parseIndicatorPrefs(raw: string | null): IndicatorPrefs {
  if (!raw) return none();
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return none();
  }
  if (parsed == null || typeof parsed !== 'object') return none();
  const o = parsed as { ma?: unknown; maCatalog?: unknown; boll?: unknown; panes?: unknown };

  // 目录：区间内的正整数、去重、保序、不超过 MA_LIMIT 条；缺失 / 坏值 / 空数组 → 回落默认。
  const catalog: number[] = [];
  if (Array.isArray(o.maCatalog)) {
    for (const v of o.maCatalog) {
      if (typeof v === 'number' && isMaPeriodLegal(v) && !catalog.includes(v) && catalog.length < MA_LIMIT) {
        catalog.push(v);
      }
    }
  }
  if (catalog.length === 0) catalog.push(...DEFAULT_MA_CATALOG);

  // 选中：合法正整数去重；不在目录里的补进目录（旧存档没有 maCatalog，别把已选的线丢了）。
  const selected: number[] = [];
  if (Array.isArray(o.ma)) {
    for (const v of o.ma) {
      if (typeof v !== 'number' || !isMaPeriodLegal(v) || selected.includes(v)) continue;
      selected.push(v);
    }
  }
  for (const p of selected) {
    if (!catalog.includes(p) && catalog.length < MA_LIMIT) catalog.push(p);
  }
  const ma = selected.filter((p) => catalog.includes(p)).sort((a, b) => a - b);

  const panes = Array.isArray(o.panes)
    ? SUB_PANE_KINDS.filter((k) => (o.panes as unknown[]).includes(k))
    : [];
  return { maCatalog: catalog, ma, boll: o.boll === true, panes };
}

export function serializeIndicatorPrefs(prefs: IndicatorPrefs): string {
  return JSON.stringify(prefs);
}