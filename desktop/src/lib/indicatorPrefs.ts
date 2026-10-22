/**
 * 看盘时叠了哪些指标（MA / EMA 两族的目录、配色与显隐 / 布林带开关 / 副图）—— 存 localStorage，重启后原样回来。
 *
 * 存的是**配置**：每族一个目录（取哪几档、什么顺序）、期数到线色的绑定、整族的显隐开关，
 * 以及每条均线自己的「小眼睛」（`muted`）。
 * 指标栏上因此只有 MA / EMA 两枚 chip（点了就是整族显隐），**不再逐档出 chip** ——
 * 逐档开关在档位多起来之后把指标栏挤成一条长龙，而「今天想看哪几条」本来就该在面板里定。
 *
 * 显隐两级：chip 是总闸（`hidden`），面板里每条行尾的小眼睛是分闸（`muted`）；
 * 总闸关掉再打开，分闸的状态原样保留。
 *
 * 读的时候逐项校验，认不出的（老版本存的、手改坏的、以后删掉的档位）一律丢掉 ——
 * 与 `lib/intervals.ts` 的 `parseIntervals` 同一套容错口径，坏值不会把界面弄崩。
 * 老存档（`{maCatalog, ma, maColors, maHidden}` 那套「目录 + 选中子集」）在这里一次性迁移到新形状。
 */

import { MA_LIMIT, SUB_PANE_KINDS, type MaKind, type SubPaneKind } from './chartSeries';
import {
  defaultCatalog,
  isMaPeriodLegal,
  normalizeMaColors,
  normalizeMuted,
  type MaFamilies,
  type MaFamilyPrefs,
} from './maPeriods';

export const INDICATORS_KEY = 'mm.indicators';

export type { MaFamilyPrefs, MaFamilies };

export interface IndicatorPrefs {
  families: MaFamilies;
  boll: boolean;
  /** 副图，可多选（按 `SUB_PANE_KINDS` 的声明顺序，空 = 不显示）。 */
  panes: SubPaneKind[];
}

/** 出厂 = 裸 K 线：两族都配好出厂档位但都处于「藏起来」状态（点一下 chip 就上图）。 */
function none(): IndicatorPrefs {
  return {
    families: {
      MA: {
        catalog: defaultCatalog('MA'),
        colors: normalizeMaColors(defaultCatalog('MA'), null),
        hidden: true,
        muted: [],
      },
      EMA: {
        catalog: defaultCatalog('EMA'),
        colors: normalizeMaColors(defaultCatalog('EMA'), null),
        hidden: true,
        muted: [],
      },
    },
    boll: false,
    panes: [],
  };
}

/** 目录归一：区间内的正整数、去重、保序、最多 `MA_LIMIT` 条；没有一项合法就回落出厂目录。 */
function parseCatalog(raw: unknown, kind: MaKind): number[] {
  const out: number[] = [];
  if (Array.isArray(raw)) {
    for (const v of raw) {
      if (typeof v === 'number' && isMaPeriodLegal(v) && !out.includes(v) && out.length < MA_LIMIT) {
        out.push(v);
      }
    }
  }
  // 老存档 / 坏值：给一份出厂目录比给空表更安全 —— 空表意味着这枚 chip 点了没反应
  return out.length > 0 ? out : defaultCatalog(kind);
}

function parseFamily(kind: MaKind, raw: unknown): MaFamilyPrefs {
  const o = raw != null && typeof raw === 'object' ? (raw as Record<string, unknown>) : {};
  const catalog = parseCatalog(o.catalog, kind);
  return {
    catalog,
    colors: normalizeMaColors(catalog, o.colors),
    // 缺省「藏起来」：出厂是裸 K，老存档的迁移路径也会显式给出这个值
    hidden: o.hidden !== false,
    // 老存档没有逐条小眼睛（那一版只有整族一个开关）⇒ 全都睁眼，屏幕观感不变
    muted: normalizeMuted(catalog, o.muted),
  };
}

/**
 * 存形：
 * `{"families":{"MA":{"catalog":[5,20],"colors":{"5":0,"20":1},"hidden":false},"EMA":{…}},"boll":false,"panes":[]}`。
 * 空/坏值回落出厂；老形状（`maCatalog`/`ma`/`maColors`/`maHidden`）就地迁移。
 */
export function parseIndicatorPrefs(raw: string | null): IndicatorPrefs {
  if (!raw) return none();
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return none();
  }
  if (parsed == null || typeof parsed !== 'object') return none();
  const o = parsed as {
    families?: unknown;
    maCatalog?: unknown;
    ma?: unknown;
    maColors?: unknown;
    maHidden?: unknown;
    boll?: unknown;
    panes?: unknown;
  };

  const panes = Array.isArray(o.panes)
    ? SUB_PANE_KINDS.filter((k) => (o.panes as unknown[]).includes(k))
    : [];

  if (o.families != null && typeof o.families === 'object') {
    const f = o.families as Record<string, unknown>;
    return {
      families: { MA: parseFamily('MA', f.MA), EMA: parseFamily('EMA', f.EMA) },
      boll: o.boll === true,
      panes,
    };
  }

  // ── 老存档迁移 ──
  // 旧模型有两层：「目录」= 指标栏上有哪些 chip，「选中子集」= 其中画着的那几条。
  // 新模型只有一层（目录即配置，整族一个开关），于是把「当时画着的那几条」升成新目录 ——
  // 屏幕上看到的东西不变；一条都没选（裸 K）则保留整个目录、置为「藏起来」。
  const legacyCatalog = Array.isArray(o.maCatalog)
    ? (o.maCatalog as unknown[]).filter(
        (v): v is number => typeof v === 'number' && isMaPeriodLegal(v),
      )
    : [];
  const legacyVisible = Array.isArray(o.ma)
    ? (o.ma as unknown[]).filter(
        (v): v is number => typeof v === 'number' && isMaPeriodLegal(v),
      )
    : [];
  const catalog = legacyVisible.length > 0
    ? parseCatalog(legacyVisible, 'MA')
    : parseCatalog(legacyCatalog, 'MA');
  const ma: MaFamilyPrefs = {
    catalog,
    colors: normalizeMaColors(catalog, o.maColors),
    hidden: o.maHidden === true || legacyVisible.length === 0,
    // 旧模型没有「逐条小眼睛」：迁移后这一族里所有档位都是睁眼的
    muted: [],
  };
  return {
    families: {
      MA: ma,
      // 老存档没有 EMA 一族：用出厂三档，先藏着（点一下 chip 才上图）
      EMA: {
        catalog: defaultCatalog('EMA'),
        colors: normalizeMaColors(defaultCatalog('EMA'), null),
        hidden: true,
        muted: [],
      },
    },
    boll: o.boll === true,
    panes,
  };
}

export function serializeIndicatorPrefs(prefs: IndicatorPrefs): string {
  return JSON.stringify(prefs);
}
