import { useState } from 'react';
import {
  addWebhook,
  removeWebhook,
  testWebhook,
  updateWebhook,
  useWebhooks,
  validateWebhookUrl,
  type WebhookEndpoint,
  type WebhookSend,
} from '../lib/webhooks';

/**
 * 「告警通知」这一整段：webhook 端点列表 + 逐条测试。
 *
 * 行内直接改、改完即存（没有保存按钮）——这里一屏最多两三条地址，
 * 「编辑 → 保存」多一步换不来什么，反而是漏点保存的根源。
 * 新增先落一条空行，名称可留空（QQ 里的标题是 notify_hub 那个 key 的名字）；
 * 地址空着或不合规时「测试」禁用、错误就地标红（应允的落盘口径见 lib/webhooks）。
 */
export function WebhookSection() {
  const endpoints = useWebhooks();
  /** 正在测试的端点 id（按钮转「测试中…」）。 */
  const [busyId, setBusyId] = useState<string | null>(null);

  const runTest = async (endpoint: WebhookEndpoint) => {
    setBusyId(endpoint.id);
    await testWebhook(endpoint);
    setBusyId(null);
  };

  return (
    <div className="set-block-body">
      <p className="up-hint">
        告警线触发时，除系统通知外还会把消息 POST 到下面的地址；notify_hub 填它的 hook 链接即可。
      </p>

      {endpoints.map((endpoint) => (
        <EndpointRow
          key={endpoint.id}
          endpoint={endpoint}
          busy={busyId === endpoint.id}
          onTest={() => void runTest(endpoint)}
        />
      ))}

      <div className="up-actions">
        <button className="up-btn" onClick={() => addWebhook('', '')}>
          ＋ 添加 Webhook
        </button>
      </div>
    </div>
  );
}

function EndpointRow({
  endpoint,
  busy,
  onTest,
}: {
  endpoint: WebhookEndpoint;
  busy: boolean;
  onTest: () => void;
}) {
  const blank = endpoint.url.trim() === '';
  const problem = blank ? null : validateWebhookUrl(endpoint.url);
  return (
    <div className="wh-row">
      <div className="wh-main">
        <input
          className="wh-input wh-name"
          value={endpoint.name}
          placeholder="名称（可选）"
          spellCheck={false}
          onChange={(e) => updateWebhook(endpoint.id, { name: e.target.value })}
        />
        <input
          className="wh-input wh-url"
          value={endpoint.url}
          placeholder="https://…/hook/<key>"
          spellCheck={false}
          autoComplete="off"
          onChange={(e) => updateWebhook(endpoint.id, { url: e.target.value })}
        />
        <button
          className="up-btn"
          disabled={blank || problem != null || busy}
          title="发一条测试消息，验证地址与 key 是否通"
          onClick={onTest}
        >
          {busy ? '测试中…' : '测试'}
        </button>
        <button className="up-btn" title="删除这个地址" onClick={() => removeWebhook(endpoint.id)}>
          删除
        </button>
      </div>
      {problem != null ? (
        <span className="up-error">{problem}</span>
      ) : (
        !blank &&
        endpoint.last && (
          <span className={`wh-status${endpoint.last.ok ? '' : ' bad'}`}>
            {statusText(endpoint.last)}
          </span>
        )
      )}
    </div>
  );
}

/** 发送结果的短句：「测试 14:32 已送达」/「上次告警 14:32 失败：连接超时」。 */
function statusText(send: WebhookSend): string {
  const what = send.test ? '测试' : '上次告警';
  return send.ok ? `${what} ${stamp(send.at)} 已送达` : `${what} ${stamp(send.at)} 失败：${send.detail}`;
}

/** 今天只给 HH:MM，隔天的带上月/日（重启后 last 还在，隔夜再看到不至于误读）。 */
function stamp(at: number): string {
  const d = new Date(at);
  const hhmm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  const today = new Date();
  return d.toDateString() === today.toDateString() ? hhmm : `${d.getMonth() + 1}/${d.getDate()} ${hhmm}`;
}
